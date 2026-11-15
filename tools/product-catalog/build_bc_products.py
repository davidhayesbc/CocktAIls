"""Build the retained BC dataset; no network, retail scraping, or user data."""
import csv
import hashlib
import json
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2] / 'catalog-packs/bc-products'
EXPECTED = '8b426567e9342e1205dcf5fb875c3c397f6e3f5f9d7e820a1ca4e205fb068406'

def gtin(raw):
    if not raw or not raw.isascii() or not raw.isdigit() or not 1 <= len(raw) <= 14:
        return None
    value = raw.zfill(14)
    check = (-sum(int(n) * (3 if i % 2 == 0 else 1) for i, n in enumerate(value[:-1][::-1]))) % 10
    return value if check == int(value[-1]) else None

def number(raw, minimum, maximum):
    try:
        value = Decimal(raw)
        if not value.is_finite() or not minimum <= value <= maximum:
            return None
        return format(value.normalize(), 'f')
    except InvalidOperation:
        return None

def build():
    source = (ROOT / 'source.csv').read_bytes()
    assert hashlib.sha256(source).hexdigest() == EXPECTED
    metadata_bytes = (ROOT / 'catalogue-package-show.json').read_bytes()
    assert hashlib.sha256(metadata_bytes).hexdigest() == 'e6fc4f6979c170a13357951ace9caab1dd985048a5786348601f63628c158d27'
    metadata = json.loads(metadata_bytes)['result']
    with (ROOT / 'source.csv').open(newline='') as f:
        rows = list(csv.DictReader(f))
    keys = Counter(gtin(r['PRODUCT_BASE_UPC_NO']) for r in rows if gtin(r['PRODUCT_BASE_UPC_NO']))
    records = []
    for row in rows:
        raw = row['PRODUCT_BASE_UPC_NO']
        key = gtin(raw)
        litres = number(row['PRODUCT_LITRES_PER_CONTAINER'], Decimal('0.001'), Decimal('20'))
        count = number(row['PRD_CONTAINER_PER_SELL_UNIT'], 1, 1000)
        abv = number(row['PRODUCT_ALCOHOL_PERCENT'], 0, 100)
        reasons = []
        if not raw: reasons.append('MISSING_UPC')
        elif not key: reasons.append('INVALID_GTIN')
        elif len(raw) <= 8: reasons.append('AMBIGUOUS_SHORT_UPC')
        if key and keys[key] > 1: reasons.append('DUPLICATE_GTIN')
        if row['ITEM_CATEGORY_NAME'] not in ('Wine', 'Spirits', 'Beer', 'Refreshment Beverages'):
            reasons.append('NOT_BEVERAGE')
        if litres is None: reasons.append('INVALID_CONTAINER_SIZE')
        if count != '1': reasons.append('NOT_SINGLE_CONTAINER')
        if not row['PRODUCT_LONG_NAME'].strip(): reasons.append('MISSING_NAME')
        records.append(dict(recordId=row['PRODUCT_SKU_NO'], rawUpc=raw, gtin14=key,
            name=row['PRODUCT_LONG_NAME'], category=row['ITEM_CATEGORY_NAME'],
            subcategory=row['ITEM_SUBCATEGORY_NAME'], sourceClass=row['ITEM_CLASS_NAME'],
            country=row['PRODUCT_COUNTRY_ORIGIN_NAME'], litres=litres, containers=count,
            abv=abv, lookupEligible=not reasons, exclusions=reasons))
    assert len({r['recordId'] for r in records}) == len(records)
    pack = dict(schemaVersion=1, sourceId='bc-liquor-products', revision=1, sourceDate='2026-07',
        snapshotDate='2026-11-14', sourceSha256=EXPECTED,
        sourceUrl=metadata['resources'][0]['url'], licenseTitle=metadata['license_title'],
        licenseUrl=metadata['license_url'], metadataIsOpen=metadata['isopen'],
        metadataModified=metadata['metadata_modified'],
        resourceModified=metadata['resources'][0]['last_modified'],
        attribution='Contains information licensed under the Open Government Licence – British Columbia.',
        records=records)
    report = dict(rows=len(rows), categories=dict(Counter(r['category'] for r in records)),
        validGtinRows=sum(bool(r['gtin14']) for r in records), uniqueValidGtins=len(keys),
        eligible=sum(r['lookupEligible'] for r in records),
        eligibleSpirits=sum(r['lookupEligible'] and r['category']=='Spirits' for r in records),
        exclusions=dict(Counter(reason for r in records for reason in r['exclusions'])))
    return pack, report

if __name__ == '__main__':
    for name, value in zip(('products.json', 'audit.json'), build()):
        content = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
        path = ROOT / name
        if '--check' in sys.argv:
            assert path.read_text() == content, name + ' differs; regenerate'
        else:
            path.write_text(content)
