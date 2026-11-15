import unittest
from build_bc_products import build, gtin, number
from decimal import Decimal

class BcProductsTest(unittest.TestCase):
    def test_recovery_and_check_digits(self):
        for raw in ('3105474011', '003105474011', '0003105474011', '00003105474011'):
            self.assertEqual('00003105474011', gtin(raw))
        for raw in ('', '3105474012', '3.105474011e9', '１２３４５６７８', '123456789012345'):
            self.assertIsNone(gtin(raw))

    def test_units_do_not_invent_or_clamp(self):
        self.assertEqual('0.75', number('0.750', Decimal('0.001'), 20))
        for raw in ('NaN', 'Infinity', '-1', '21', '750ml'):
            self.assertIsNone(number(raw, Decimal('0.001'), 20))

    def test_snapshot_counts_and_ambiguity(self):
        pack, report = build()
        self.assertEqual((8224, 7567, 7566, 6988, 1561), tuple(report[k] for k in ('rows','validGtinRows','uniqueValidGtins','eligible','eligibleSpirits')))
        rows = pack['records']
        duplicate = [r for r in rows if r['gtin14']=='00062067427152']
        self.assertEqual({'1','6'}, {r['containers'] for r in duplicate})
        self.assertTrue(all(not r['lookupEligible'] for r in duplicate))
        short = next(r for r in rows if r['rawUpc']=='89005439')
        self.assertIn('AMBIGUOUS_SHORT_UPC', short['exclusions'])
        self.assertFalse(short['lookupEligible'])
        self.assertFalse(pack['metadataIsOpen'])
        self.assertEqual('2026-07', pack['sourceDate'])

if __name__ == '__main__': unittest.main()
