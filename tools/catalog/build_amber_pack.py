"""Deterministic data-only conversion of the reviewed, relayed Amber Book records."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "catalog-packs/amber-book"
SOURCE_SHA = "8c0fe99512b0327924e0342bd0ba01cbf5235da23e45851dd3851b8225c6e002"
source_bytes = (DEST / "source-records.json").read_bytes()
assert hashlib.sha256(source_bytes).hexdigest() == SOURCE_SHA
records = json.loads(source_bytes)
assert len(records) == len({r["id"] for r in records}) == 48
notice = (DEST / "LICENSE").read_text()

aliases = {
    "Bourbon": "Bourbon whiskey", "Bourbon or rye": "Bourbon or rye whiskey",
    "Fresh lemon juice": "Lemon juice", "Fresh lime juice": "Lime juice",
    "Orange liqueur (triple sec)": "Triple sec", "Egg white (optional)": "Egg white",
}
spirits = {"Bourbon whiskey", "Bourbon or rye whiskey", "Rye whiskey", "Irish whiskey", "Gin", "Vodka",
           "Citrus vodka", "White rum", "Light rum", "Dark rum", "Aged rum", "Spiced rum", "Blanco tequila", "Cognac"}
recipes = []
for r in records:
    base = r["category"] if r["category"] in {"Whiskey", "Gin", "Rum", "Tequila", "Vodka", "Brandy"} else None
    if r["id"] in {"french-75", "french-blonde"}: base = "Gin"
    bubbly = r["category"] == "Bubbly" and r["id"] != "french-blonde"
    mocktail = r["category"] == "Mocktail"
    style_label = "Bubbly" if bubbly else " / ".join(t.title() for t in r["tags"] if t in {"shaken", "stirred", "muddled", "blended", "hot", "fizzy"})
    display = f"Base spirit: {base or 'None specified'}\nStyle: {style_label}\nGlass: {r['glass']}"
    if mocktail: display += "\nSource designation: Mocktail; this is not a measured alcohol or allergen declaration."
    display += f"\nSource strength: {r['strength']} (editorial, not measured ABV)\nSource difficulty: {r['difficulty']}\n{r['description']}"
    instructions = "\n".join(f"{i+1}. {step}" for i, step in enumerate(r["method"]))
    if r["garnish"] != "None": instructions += "\nGarnish: " + r["garnish"]
    modifications = "Names, instructions and source quantities retained. Ingredient aliases are explicit spelling/qualifier mappings. Quantities are not converted or automatically scaled; one source formulation is displayed."
    if r["id"] == "french-blonde": modifications += " French Blonde classified as gin-based rather than Bubbly: its listed ingredients contain no sparkling component. Original category retained in the source record."
    if r["id"] == "french-75": modifications += " Gin base and Bubbly style recorded separately."
    lines = []
    for line in r["ingredients"]:
        name = aliases.get(line["item"], line["item"])
        lines.append(dict(name=name, category="Spirit" if name in spirits else "Other", amount=None,
                          unit=line["amount"], optional=line["item"].endswith("(optional)")))
    recipes.append(dict(recordId=r["id"], title=r["name"], style="HOT" if "hot" in r["tags"] else "OTHER",
                        servings=1, instructions=instructions, permanentUrl=None, historyUrl=None,
                        modifications=modifications, originalIngredients="; ".join(f"{x['amount']} {x['item']}" for x in r["ingredients"]),
                        ingredients=lines, familyAliases=["Dry Martini"] if r["id"] == "martini" else [],
                        sourceMetadata=dict(baseSpirit=base, styleLabel=style_label, mocktail=mocktail, glass=r["glass"],
                                            rawQuantities=True, displayDescription=display, originalRecord=r)))
pack = dict(format="cocktails.catalog-pack.v2", id="uploaded-amber-book", version=1, sourceKind="UPLOADED_DATA",
            sourceName="The Amber Book (uploaded collection)", license="MIT", licenseUrl="https://opensource.org/license/mit",
            attribution="The Amber Book, supplied in TheAmberBook-main.zip. Copyright (c) 2026. The supplied notice names no copyright holder; upstream repository identity is unconfirmed.",
            repositoryUrl=None, repositoryRevision="sha256:"+SOURCE_SHA, licenseNotice=notice, recipes=recipes)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true", help="Verify checked-in runtime data without rewriting it")
args = parser.parse_args()
rendered = json.dumps(pack, ensure_ascii=False, indent=2)+"\n"
if args.check:
    assert (DEST / "recipes.json").read_text() == rendered, "Generated Amber catalog differs; run tools/catalog/build_amber_pack.py"
else:
    (DEST / "recipes.json").write_text(rendered)
print(f"Verified {len(recipes)} recipes, {sum(r['sourceMetadata']['mocktail'] for r in recipes)} designated mocktails; original SHA256 verified.")
