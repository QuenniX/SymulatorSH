"""
Jednorazowy eksport surowych danych badawczych na dysk.

PO CO TO JEST
  Retencja bucketu InfluxDB na planie darmowym wynosi 30 dni i liczona jest od
  ZNACZNIKA CZASOWEGO punktu, nie od momentu zapisu. Pomiary partii maja znaczniki
  rozciagniete na 30 dob wstecz, wiec wyparowuja stopniowo - doba po dobie - od
  dnia nastepnego po uruchomieniu partii. Po dwoch tygodniach polowa danych juz
  nie istnieje i nie da sie jej odtworzyc inaczej niz powtarzajac cala partie.

  Ten skrypt zrzuca komplet danych zrodlowych do plikow. Po jego uruchomieniu
  analiza przestaje zalezec od tego, czy EC2 i InfluxDB zyja, a regeneracja
  dowolnego wykresu jest natychmiastowa. Zrzut stanowi tez zalacznik do pracy.

  URUCHOM ZARAZ PO ZAKONCZENIU PARTII. Kazda doba zwloki to 24 godziny danych mniej.

WYJSCIE
  analiza/dane_zrodlowe/
      manifest.json                    - metadane zrzutu, liczniki, wynik walidacji
      testy.json                       - lista testow partii z konfiguracjami
      profile/<KOD>_<Sezon>.json       - surowa odpowiedz /tests/{id}/costs (24 pliki)
      profile/<KOD>_<Sezon>.csv        - hourlyBreakdown w formie tabelarycznej
      ceny/<Sezon>.json                - surowa odpowiedz /prices/range (4 pliki)
      ceny/wszystkie.csv               - wszystkie ceny w jednym pliku

URUCHOMIENIE
    python eksport_danych.py
    python eksport_danych.py --prefix "[Partia 2026-08-18T09:38]"   # jesli autodetekcja zawiedzie
"""
import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import requests

BASE_URL = "http://3.77.28.199/api/v1"
TU = Path(__file__).resolve().parent
KEY_FILE = TU / "api_key.txt"
OUT = TU / "dane_zrodlowe"

SEZONY = {
    "Zima":   (date(2025, 1, 1),  date(2025, 1, 30)),
    "Wiosna": (date(2025, 4, 1),  date(2025, 4, 30)),
    "Lato":   (date(2025, 7, 1),  date(2025, 7, 30)),
    "Jesien": (date(2025, 10, 1), date(2025, 10, 30)),
}
WZORZEC_NAZWY = re.compile(
    r"Profil\s+(?P<kod>[A-F])[:\s]+(?P<etykieta>.+?)\s+-\s+(?P<sezon>Zima|Wiosna|Lato|Jesien|Jesień)",
    re.IGNORECASE)
WZORZEC_PARTII = re.compile(r"^\[(?P<partia>[^\]]+)\]")

OCZEKIWANE_TESTY = 24
OCZEKIWANE_GODZINY = 720


def klucz_api():
    if not KEY_FILE.exists():
        sys.exit(f"[BLAD] brak {KEY_FILE} - uruchom najpierw analiza_wyniki.py albo wklej klucz recznie")
    return KEY_FILE.read_text(encoding="utf-8").strip()


def get(sciezka, key, **params):
    r = requests.get(f"{BASE_URL}{sciezka}", headers={"X-API-Key": key}, params=params, timeout=120)
    r.raise_for_status()
    return r.json()


def wykryj_partie(testy):
    """Zwraca prefiks partii z najwieksza liczba testow (najswiezsza przy remisie)."""
    licznik = Counter()
    for t in testy:
        m = WZORZEC_PARTII.match(t.get("name", ""))
        if m:
            licznik[m.group("partia")] += 1
    if not licznik:
        return None
    najlepsza = max(licznik.items(), key=lambda kv: (kv[1], kv[0]))
    return f"[{najlepsza[0]}]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prefix", help="prefiks partii; domyslnie wykrywany automatycznie")
    args = ap.parse_args()

    key = klucz_api()
    (OUT / "profile").mkdir(parents=True, exist_ok=True)
    (OUT / "ceny").mkdir(parents=True, exist_ok=True)

    print("\n[1/4] Lista testow")
    wszystkie = get("/tests", key)
    prefix = args.prefix or wykryj_partie(wszystkie)
    if not prefix:
        sys.exit("[BLAD] nie wykryto zadnej partii - podaj --prefix recznie")
    partia = [t for t in wszystkie if t.get("name", "").startswith(prefix)]
    print(f"      partia: {prefix}")
    print(f"      testow: {len(partia)}")

    niezakonczone = [t for t in partia if t.get("status") != "COMPLETED"]
    if niezakonczone:
        print(f"      [UWAGA] {len(niezakonczone)} testow nie ma statusu COMPLETED:")
        for t in niezakonczone:
            print(f"              {t.get('status'):10s} {t.get('name')}")

    print("\n[2/4] Profile godzinowe")
    problemy = []
    meta = []
    for i, t in enumerate(sorted(partia, key=lambda x: x["name"]), 1):
        m = WZORZEC_NAZWY.search(t["name"])
        if not m:
            problemy.append(f"nie umiem sparsowac nazwy: {t['name']}")
            continue
        kod = m.group("kod").upper()
        sezon = m.group("sezon").capitalize().replace("ń", "n")
        nazwa = f"{kod}_{sezon}"

        szczegoly = get(f"/tests/{t['testId']}", key)
        koszty = get(f"/tests/{t['testId']}/costs", key)
        godziny = koszty.get("hourlyBreakdown", [])
        print(f"      ({i:2d}/{len(partia)}) {nazwa:12s} {len(godziny):4d} godzin", end="")

        if len(godziny) != OCZEKIWANE_GODZINY:
            print(f"   [!] oczekiwano {OCZEKIWANE_GODZINY}")
            problemy.append(f"{nazwa}: {len(godziny)} godzin zamiast {OCZEKIWANE_GODZINY}")
        else:
            print("   ok")

        (OUT / "profile" / f"{nazwa}.json").write_text(
            json.dumps({"test": szczegoly, "costs": koszty}, ensure_ascii=False, indent=1),
            encoding="utf-8")

        if godziny:
            pola = list(godziny[0].keys())
            with open(OUT / "profile" / f"{nazwa}.csv", "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, fieldnames=pola)
                w.writeheader()
                w.writerows(godziny)

        meta.append({"plik": nazwa, "testId": t["testId"], "nazwa": t["name"],
                     "profil": kod, "sezon": sezon, "status": t.get("status"),
                     "godzin": len(godziny),
                     "kwhSuma": koszty.get("totalKwh"),
                     "godzinBezCenyRdn": koszty.get("hoursWithMissingRdnPrice")})

    (OUT / "testy.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")

    print("\n[3/4] Ceny RDN")
    wszystkie_ceny = []
    for sezon, (od, do) in SEZONY.items():
        ceny = get("/prices/range", key, **{"from": od.isoformat(), "to": do.isoformat()})
        (OUT / "ceny" / f"{sezon}.json").write_text(
            json.dumps(ceny, ensure_ascii=False, indent=1), encoding="utf-8")
        doby = defaultdict(set)
        for p in ceny:
            doby[str(p["deliveryDate"])[:10]].add(int(p["hour"]))
            wszystkie_ceny.append({"sezon": sezon, "data": str(p["deliveryDate"])[:10],
                                   "godz": p["hour"], "cena_pln_mwh": p["pricePlnMwh"]})
        niepelne = {d: sorted(set(range(24)) - g) for d, g in doby.items() if len(g) != 24}
        oznaczenie = "ok" if len(doby) == 30 and not niepelne else "[!]"
        print(f"      {sezon:7s} {len(doby):2d} dob, {len(ceny):4d} godzin   {oznaczenie}")
        if len(doby) != 30:
            problemy.append(f"ceny {sezon}: {len(doby)} dob zamiast 30")
        for d, g in niepelne.items():
            problemy.append(f"ceny {sezon} {d}: brak godzin {g}")

    with open(OUT / "ceny" / "wszystkie.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["sezon", "data", "godz", "cena_pln_mwh"])
        w.writeheader()
        w.writerows(wszystkie_ceny)

    print("\n[4/4] Manifest i walidacja")
    if len(partia) != OCZEKIWANE_TESTY:
        problemy.insert(0, f"partia ma {len(partia)} testow zamiast {OCZEKIWANE_TESTY}")

    manifest = {
        "zrzutWykonany": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "zrodlo": BASE_URL,
        "prefiksPartii": prefix,
        "liczbaTestow": len(partia),
        "liczbaCen": len(wszystkie_ceny),
        "walidacja": "OK" if not problemy else "PROBLEMY",
        "problemy": problemy,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1),
                                       encoding="utf-8")

    print()
    if problemy:
        print(f"      [PROBLEMY] {len(problemy)}:")
        for p in problemy:
            print(f"        - {p}")
        print("\n      Dane zostaly mimo to zapisane. Rozstrzygnij problemy przed analiza.")
    else:
        print("      [OK] komplet: 24 testy x 720 godzin, 4 sezony x 30 dob x 24 godziny")
    print(f"\n      Zrzut: {OUT}")
    print()


if __name__ == "__main__":
    main()
