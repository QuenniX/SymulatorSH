"""
Scenariusz aktywnego sterowania (demand response) - wariant B.

Liczy, ile zmienilby sie bilans taryf, gdyby gospodarstwo przesuwalo elastyczne
odbiorniki w odpowiedzi na sygnal cenowy, zamiast zuzywac energie biernie.

ZALOZENIA
  Elastyczne (przesuwalne bez utraty komfortu):
    - PRALKA i ZMYWARKA - caly cykl przenoszony do najtanszego ciaglego okna,
      z zakonczeniem nie pozniej niz o 07:00 (pranie/zmywanie gotowe na rano).
    - BOJLER - dobowa energia rozdzielana na najtansze godziny doby, przy
      ograniczeniu mocy grzalki oraz wymogu co najmniej jednej godziny grzania
      w oknie 04:00-07:00 i jednej w 16:00-19:00 (cieplada woda rano i wieczorem).
  Sztywne: oswietlenie, czajnik, piekarnik, TV, komputer, router, lodowka,
  klimatyzacja, grzejniki (komfort cieplny - wariant C, tu pominiety).

  Kazda taryfa optymalizuje sie po swojemu, bo sterownik zna swoj cennik:
    - G11: cena plaska, przesuniecie nic nie zmienia
    - G12: elastyczne obciazenia do strefy nocnej
    - RDN: elastyczne obciazenia do najtanszych godzin danej doby

  To jest kontrfaktyk liczony EX POST przy doskonalej znajomosci cen na dobe
  naprzod (RDN publikuje o 14:00, wiec zalozenie jest realistyczne) oraz
  bezkosztowym przesunieciu (zalozenie optymistyczne). Wynik stanowi GORNE
  ograniczenie wartosci prostego sterowania; scenariusz bierny z rozdzialu 7
  jest ograniczeniem dolnym.

WEJSCIE
  archetypes/seasonal/*.json          - konfiguracje profili
  analiza/ceny_sezony_2025.csv        - godzinowe ceny hurtowe (sezon,data,godz,cena)

URUCHOMIENIE
  python demand_response.py
"""
import csv
import json
import math
from collections import defaultdict
from pathlib import Path

# --- parametry taryfowe 2026 (spojne z TariffParams.java i analiza_wyniki.py) ---
G11 = 1.10
G12_DZIEN, G12_NOC = 1.25, 0.62
G12_NIGHT_HOURS = {22, 23, 0, 1, 2, 3, 4, 5, 13, 14}
DYST, AKCYZA, MARZA, VAT = 0.33, 0.005, 0.10, 1.23

SEZONY = ["Zima", "Wiosna", "Lato", "Jesien"]
ELASTYCZNE = {"WASHER", "DISHWASHER", "BOILER"}
STANOWE = {"LIGHT", "TV", "HEATER", "ROUTER", "AC", "COMPUTER"}

ROOT = Path(__file__).resolve().parent.parent
SEASONAL = ROOT / "archetypes" / "seasonal"
CENY = Path(__file__).resolve().parent / "ceny_sezony_2025.csv"

# ograniczenia komfortu (wariant B)
DEADLINE_AGD = 7          # pralka/zmywarka maja byc gotowe do 07:00
OKNA_CWU = [(4, 7), (16, 19)]   # bojler: min. 1 h grzania w kazdym z tych okien


def rdn_detal(cena_hurt_mwh):
    return (cena_hurt_mwh / 1000.0 + DYST + AKCYZA + MARZA) * VAT


def g12_cena(h):
    return G12_NOC if h in G12_NIGHT_HOURS else G12_DZIEN


# ---------------------------------------------------------------- profil
def _min(t):
    h, m = map(int, t.split(":"))
    return h * 60 + m


def minuty_on(dev):
    s = dev.get("schedule")
    per_h = [0] * 24
    if s == "always_on":
        return [60] * 24
    if s == "always_off" or not isinstance(s, list):
        return per_h
    tl = {0: False}
    for ev in s:
        tl[_min(ev["at"])] = ev.get("action", "ON").upper() == "ON"
    klucze = sorted(tl)
    stan, i = False, 0
    for m in range(1440):
        while i < len(klucze) and klucze[i] <= m:
            stan = tl[klucze[i]]
            i += 1
        if stan:
            per_h[m // 60] += 1
    return per_h


def kwh_na_godzine(dev):
    """24-elementowy wektor kWh dla jednego urzadzenia."""
    t, p = dev["type"], dev.get("params", {})
    oh = minuty_on(dev)
    if t in ("BOILER", "REFRIGERATOR", "AC"):
        cyc = p.get("cycle_length_minutes", 43)
        duty = round(cyc * p.get("duty_cycle", 0.4)) / cyc
        return [p.get("power_w", 0) * (m / 60) * duty / 1000 for m in oh]
    if t == "COMPUTER":
        L, I = p.get("burst_length_minutes", 5), p.get("burst_interval_minutes", 20)
        d = L / (L + (I - 1) / 2)
        w = d * p.get("burst_power_w", 300) + (1 - d) * p.get("idle_power_w", 100)
        return [w * (m / 60) / 1000 for m in oh]
    if t in STANOWE:
        return [p.get("power_w", 0) * (m / 60) / 1000 for m in oh]

    out = [0.0] * 24
    sched = dev.get("schedule") if isinstance(dev.get("schedule"), list) else []
    for ev in sched:
        if ev.get("action", "ON").upper() != "ON":
            continue
        start = _min(ev["at"])
        if t == "DISHWASHER":
            fazy = [(p.get("heat_phase_minutes", 10), p.get("heat_power_w", 1800)),
                    (p.get("wash_phase_minutes", 60), p.get("wash_power_w", 200)),
                    (p.get("dry_phase_minutes", 20), p.get("dry_power_w", 1500))]
        elif t == "OVEN":
            on_m, off_m = p.get("heat_on_minutes", 5), p.get("heat_off_minutes", 3)
            fazy = [(p.get("cycle_minutes", 60), p.get("power_w", 2500) * on_m / (on_m + off_m))]
        else:
            fazy = [(p.get("cycle_minutes", 3 if t == "KETTLE" else 60),
                     p.get("power_w", 2000))]
        kursor = start
        for dur, w in fazy:
            for i in range(int(dur)):
                m = kursor + i
                if m >= 1440:
                    break
                out[m // 60] += w / 60 / 1000
            kursor += int(dur)
    return out


def rozbij_profil(cfg):
    """Zwraca (sztywne[24], lista_cykli_agd, bojler_kwh_doba, bojler_moc_kw)."""
    sztywne = [0.0] * 24
    cykle = []          # [(kwh_cyklu, dlugosc_godzin)]
    boj_kwh, boj_kw = 0.0, 0.0
    for dev in cfg["devices"]:
        wek = kwh_na_godzine(dev)
        t = dev["type"]
        if t == "BOILER":
            boj_kwh += sum(wek)
            boj_kw = max(boj_kw, dev.get("params", {}).get("power_w", 0) / 1000)
        elif t in ("WASHER", "DISHWASHER"):
            p = dev.get("params", {})
            if t == "DISHWASHER":
                dl = (p.get("heat_phase_minutes", 10) + p.get("wash_phase_minutes", 60)
                      + p.get("dry_phase_minutes", 20))
            else:
                dl = p.get("cycle_minutes", 60)
            sched = dev.get("schedule") if isinstance(dev.get("schedule"), list) else []
            n = sum(1 for e in sched if e.get("action", "ON").upper() == "ON")
            if n:
                for _ in range(n):
                    cykle.append((sum(wek) / n, max(1, math.ceil(dl / 60))))
        else:
            for h in range(24):
                sztywne[h] += wek[h]
    return sztywne, cykle, boj_kwh, boj_kw


# ---------------------------------------------------------------- sterowanie
def okna_startu(dlugosc):
    """Godziny startu dopuszczalne przy wymogu zakonczenia do DEADLINE_AGD."""
    ok = []
    for s in range(24):
        koniec = s + dlugosc
        # cykl moze przechodzic przez polnoc; liczymy godzine zakonczenia mod 24
        if s >= 21 or koniec <= DEADLINE_AGD:
            if (koniec if s < DEADLINE_AGD else koniec - 24) <= DEADLINE_AGD:
                ok.append(s)
    return ok or list(range(24))


def rozloz_agd(ceny, cykle):
    """Umieszcza kazdy cykl w najtanszym dopuszczalnym oknie. Zwraca wektor[24]."""
    wek = [0.0] * 24
    for kwh, dl in cykle:
        best, best_koszt = None, None
        for s in okna_startu(dl):
            godz = [(s + i) % 24 for i in range(dl)]
            koszt = sum(ceny[g] for g in godz) / dl
            if best_koszt is None or koszt < best_koszt:
                best, best_koszt = godz, koszt
        for g in best:
            wek[g] += kwh / dl
    return wek


def rozloz_bojler(ceny, kwh_doba, moc_kw):
    """Grzanie w najtanszych godzinach, z minimum po jednej godzinie w oknach CWU."""
    wek = [0.0] * 24
    if kwh_doba <= 0:
        return wek
    moc = moc_kw if moc_kw > 0 else kwh_doba / 24
    zostalo = kwh_doba
    wybrane = []
    for a, b in OKNA_CWU:                       # najpierw wymogi komfortu
        g = min(range(a, b), key=lambda x: ceny[x])
        wybrane.append(g)
    for g in wybrane:
        por = min(moc, zostalo)
        wek[g] += por
        zostalo -= por
    for g in sorted(range(24), key=lambda x: ceny[x]):   # reszta - najtaniej
        if zostalo <= 1e-9:
            break
        if g in wybrane:
            continue
        por = min(moc, zostalo)
        wek[g] += por
        zostalo -= por
    if zostalo > 1e-9:                          # nie zmiescilo sie - rozlóż resztę
        for g in range(24):
            wek[g] += zostalo / 24
    return wek


# ---------------------------------------------------------------- main
def main():
    if not CENY.exists():
        print(f"[BLAD] Brak {CENY}. Najpierw zrzuc ceny (polecenie w czacie).")
        return

    def liczba(x):
        # Export-Csv w polskim locale zapisuje separator dziesietny jako przecinek.
        return float(str(x).replace("\u00a0", "").replace(" ", "").replace(",", "."))

    ceny = defaultdict(lambda: defaultdict(dict))   # sezon -> data -> godz -> zl/MWh
    with open(CENY, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            d = str(r["data"])[:10]
            ceny[r["sezon"]][d][int(r["godz"])] = liczba(r["cena_pln_mwh"])

    print()
    print("KOMPLETNOSC DANYCH CENOWYCH")
    from datetime import date, timedelta
    zakresy = {"Zima": (date(2025, 1, 1), date(2025, 1, 30)),
               "Wiosna": (date(2025, 4, 1), date(2025, 4, 30)),
               "Lato": (date(2025, 7, 1), date(2025, 7, 30)),
               "Jesien": (date(2025, 10, 1), date(2025, 10, 30))}
    for s in SEZONY:
        dni = ceny.get(s, {})
        od, do = zakresy[s]
        oczekiwane = []
        d = od
        while d <= do:
            oczekiwane.append(d.isoformat())
            d += timedelta(days=1)
        brak_dob = [x for x in oczekiwane if x not in dni]
        niepelne = {d: sorted(set(range(24)) - set(h)) for d, h in dni.items() if len(h) != 24}
        godz = sum(len(h) for h in dni.values())
        stan = "OK" if not brak_dob and not niepelne else "NIEKOMPLETNE"
        print(f"  {s:7s}: {len(dni):2d}/30 dob, {godz:3d}/720 godzin  [{stan}]")
        if brak_dob:
            print(f"           brakujace doby: {', '.join(brak_dob)}")
        if niepelne:
            for d, g in niepelne.items():
                print(f"           {d}: brak godzin {g}")
    print()

    naglowek = (f"{'Profil':26s} {'Sezon':7s} {'elast.':>7s} "
                f"{'RDN/G11 bier.':>13s} {'RDN/G11 akt.':>13s} "
                f"{'RDN/G12 bier.':>13s} {'RDN/G12 akt.':>13s}")
    print(naglowek)
    print("-" * len(naglowek))

    zbiorczo = []
    for f in sorted(SEASONAL.glob("*_zima.json")):
        stem = f.name[:-10]
        for sez in SEZONY:
            cfg = json.load(open(SEASONAL / f"{stem}_{sez.lower()}.json", encoding="utf-8"))
            etykieta = cfg.get("name", stem).split(" - ")[0]
            sztywne, cykle, boj_kwh, boj_kw = rozbij_profil(cfg)

            bazowy = [sztywne[h] for h in range(24)]
            for kwh, dl in cykle:
                pass
            # profil bazowy = sztywne + elastyczne w oryginalnych godzinach
            for dev in cfg["devices"]:
                if dev["type"] in ELASTYCZNE:
                    for h, v in enumerate(kwh_na_godzine(dev)):
                        bazowy[h] += v

            elast_kwh = sum(k for k, _ in cykle) + boj_kwh
            udzial = elast_kwh / sum(bazowy) if sum(bazowy) else 0

            k = dict(g11=0.0, g12_b=0.0, g12_a=0.0, rdn_b=0.0, rdn_a=0.0)
            for data, godziny in sorted(ceny[sez].items()):
                cd = {h: rdn_detal(godziny[h]) for h in range(24) if h in godziny}
                if len(cd) != 24:
                    continue
                # --- bierny ---
                k["g11"] += sum(bazowy[h] * G11 for h in range(24))
                k["g12_b"] += sum(bazowy[h] * g12_cena(h) for h in range(24))
                k["rdn_b"] += sum(bazowy[h] * cd[h] for h in range(24))
                # --- aktywny: kazda taryfa optymalizuje po swojemu ---
                akt_rdn = list(sztywne)
                for h, v in enumerate(rozloz_agd(cd, cykle)):
                    akt_rdn[h] += v
                for h, v in enumerate(rozloz_bojler(cd, boj_kwh, boj_kw)):
                    akt_rdn[h] += v
                k["rdn_a"] += sum(akt_rdn[h] * cd[h] for h in range(24))

                cg12 = {h: g12_cena(h) for h in range(24)}
                akt_g12 = list(sztywne)
                for h, v in enumerate(rozloz_agd(cg12, cykle)):
                    akt_g12[h] += v
                for h, v in enumerate(rozloz_bojler(cg12, boj_kwh, boj_kw)):
                    akt_g12[h] += v
                k["g12_a"] += sum(akt_g12[h] * cg12[h] for h in range(24))

            if k["g11"] == 0:
                continue
            b11 = (k["g11"] - k["rdn_b"]) / k["g11"] * 100
            a11 = (k["g11"] - k["rdn_a"]) / k["g11"] * 100
            b12 = (k["g12_b"] - k["rdn_b"]) / k["g12_b"] * 100
            a12 = (k["g12_a"] - k["rdn_a"]) / k["g12_a"] * 100
            print(f"{etykieta:26s} {sez:7s} {udzial:6.1%} "
                  f"{b11:12.1f}% {a11:12.1f}% {b12:12.1f}% {a12:12.1f}%")
            zbiorczo.append((b11, a11, b12, a12, k))

    if zbiorczo:
        n = len(zbiorczo)
        print("-" * len(naglowek))
        print(f"{'SREDNIA (24 przypadki)':34s} {'':7s} "
              f"{sum(z[0] for z in zbiorczo)/n:12.1f}% {sum(z[1] for z in zbiorczo)/n:12.1f}% "
              f"{sum(z[2] for z in zbiorczo)/n:12.1f}% {sum(z[3] for z in zbiorczo)/n:12.1f}%")
        sg11 = sum(z[4]["g11"] for z in zbiorczo)
        print(f"{'UJECIE KWOTOWE (suma zl)':34s} {'':7s} "
              f"{(sg11 - sum(z[4]['rdn_b'] for z in zbiorczo))/sg11*100:12.1f}% "
              f"{(sg11 - sum(z[4]['rdn_a'] for z in zbiorczo))/sg11*100:12.1f}%")
    print()
    print("Progi opłacalności (2026): RDN < G11 gdy hurt < 459 zl/MWh;")
    print("RDN < G12 dzien gdy hurt < 581 zl/MWh; RDN < G12 noc gdy hurt < 69 zl/MWh.")
    print()


if __name__ == "__main__":
    main()
