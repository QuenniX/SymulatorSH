"""
Jedno zrodlo prawdy o parametrach taryfowych uzytych w analizie.

Wszystkie skrypty analityczne powinny importowac stad stawki i definicje stref,
zamiast powielac je u siebie. Zmiana taryfy = edycja tego jednego pliku.

ZRODLA (dokumenty urzedowe, stan na rok 2026)
  [O] PGE Obrot S.A., "Taryfa dla energii elektrycznej dla Odbiorcow z grup
      taryfowych G", obowiazujaca od 1 stycznia 2026 r., pkt 5 "Ceny za energie
      elektryczna" - ceny netto energii czynnej.
  [D] PGE Dystrybucja S.A., "Taryfa dla uslug dystrybucji energii elektrycznej",
      tekst jednolity od 1 lutego 2026 r., tabela "Grupy taryfowe G" (str. 58)
      oraz rozdz. "Strefy czasowe stosowane w rozliczeniach z odbiorcami"
      (str. 15, tabela dla grup C12b, G12).

  PGE Dystrybucja jest operatorem wlasciwym dla Rzeszowa, co uzasadnia wybor
  tej taryfy jako podstawy modelu kosztowego.

SKLADNIKI CENY (netto, zl/kWh)
                         G11        G12 dzien   G12 noc
  energia czynna    [O]  0,4982     0,5656      0,3718
  skladnik zmienny
  stawki sieciowej  [D]  0,3469     0,4014      0,0765
  stawka jakosciowa [D]  0,0332     0,0332      0,0332
  oplata OZE        [D]  0,0073     0,0073      0,0073
  oplata kogener.   [D]  0,0030     0,0030      0,0030
  akcyza                 0,0050     0,0050      0,0050
  -----------------------------------------------------
  razem netto            0,8936     1,0155      0,4968
  brutto (x1,23)         1,0991     1,2491      0,6111

  Oplata mocowa NIE wchodzi do ceny zmiennej: dla gospodarstw domowych
  (art. 89a ust. 1 pkt 1 ustawy o rynku mocy) jest oplata ryczaltowa
  4,29 / 10,31 / 17,18 / 24,05 zl/mies. wg rocznego zuzycia [D]. Podobnie
  skladnik staly stawki sieciowej i oplata abonamentowa - to oplaty stale,
  poza modelem kosztu zmiennego.

STREFY CZASOWE W GRUPIE G12  [D, str. 15]
  Okres letni  (1 IV - 30 IX): dzienna 6-15 i 17-22;  nocna 15-17 i 22-6
  Okres zimowy (1 X  - 31 III): dzienna 6-13 i 15-22;  nocna 13-15 i 22-6
  Strefa tansza obejmuje 10 godzin na dobe w obu okresach.

  Sezony badawcze -> okresy taryfowe:
      Zima   = styczen     -> zimowy -> okno 13-15
      Wiosna = kwiecien    -> letni  -> okno 15-17
      Lato   = lipiec      -> letni  -> okno 15-17
      Jesien = pazdziernik -> zimowy -> okno 13-15

  UWAGA [O, pkt 3.2.2]: zegary sterujace ustawia sie wg czasu zimowego i nie
  zmienia w okresie czasu letniego, o ile urzadzenia nie utrzymuja godzin stref
  automatycznie. Przy interpretacji doslownej granice stref w czasie lokalnym
  letnim przesuwaja sie o +1 h. Przyjeto wariant z automatycznym utrzymaniem
  godzin (liczniki zdalnego odczytu), a wariant przesuniety policzono jako
  analize wrazliwosci - patrz wrazliwosc_g12.py.
"""

# ------------------------------------------------------------------ skladniki
ENERGIA_G11 = 0.4982
ENERGIA_G12_DZIEN = 0.5656
ENERGIA_G12_NOC = 0.3718

SIEC_G11 = 0.3469
SIEC_G12_DZIEN = 0.4014
SIEC_G12_NOC = 0.0765

JAKOSCIOWA = 0.0332
OZE = 0.0073
KOGENERACYJNA = 0.0030
AKCYZA = 0.0050
VAT = 1.23

# oplaty systemowe doliczane niezaleznie od grupy taryfowej
SYSTEMOWE = JAKOSCIOWA + OZE + KOGENERACYJNA + AKCYZA      # 0,0485


def _brutto(energia, siec):
    return (energia + siec + SYSTEMOWE) * VAT


# ------------------------------------------------------------------ taryfy stale
G11 = _brutto(ENERGIA_G11, SIEC_G11)                # 1,0991
G12_DZIEN = _brutto(ENERGIA_G12_DZIEN, SIEC_G12_DZIEN)   # 1,2491
G12_NOC = _brutto(ENERGIA_G12_NOC, SIEC_G12_NOC)         # 0,6111

# ------------------------------------------------------------------ taryfa RDN
# Odbiorca taryfy dynamicznej rozliczany jest dystrybucyjnie w grupie G11,
# placi wiec te same stawki sieciowe i systemowe; roznica dotyczy wylacznie
# skladnika energii, ktory zastepuje sie cena hurtowa RDN powiekszona o marze
# sprzedawcy. Marza jest JEDYNYM parametrem swobodnym modelu kosztowego.
MARZA = 0.10
NARZUT = SIEC_G11 + SYSTEMOWE + MARZA               # 0,4954

# Wartosc uzyta we wczesniejszej wersji analizy (0,33 + 0,005 + 0,10).
# Pomijala stawke jakosciowa, oplate OZE i kogeneracyjna, zanizajac koszt RDN
# o 0,0604 zl/kWh netto wzgledem stawek, ktore odbiorca faktycznie ponosi.
NARZUT_POPRZEDNI = 0.435

# ------------------------------------------------------------------ strefy G12
STREFY_SEZONOWE = True
PRZESUNIECIE_LETNIE = 0        # 1 = interpretacja doslowna pkt 3.2.2 [O]

NOC_CALODOBOWA = {22, 23, 0, 1, 2, 3, 4, 5}
OKNO_ZIMOWE = {13, 14}
OKNO_LETNIE = {15, 16}
SEZONY_LETNIE = {"Wiosna", "Lato"}

SEZONY = ["Zima", "Wiosna", "Lato", "Jesien"]


def godziny_nocne(sezon):
    """Zbior godzin objetych strefa tansza taryfy G12 w danym sezonie."""
    if not STREFY_SEZONOWE:
        h = NOC_CALODOBOWA | OKNO_ZIMOWE
    else:
        h = NOC_CALODOBOWA | (OKNO_LETNIE if sezon in SEZONY_LETNIE else OKNO_ZIMOWE)
    if PRZESUNIECIE_LETNIE and sezon in SEZONY_LETNIE:
        h = {(x + PRZESUNIECIE_LETNIE) % 24 for x in h}
    return h


def g12_cena(h, sezon, dzien=None, noc=None):
    """Cena G12 dla godziny h. Stawki mozna nadpisac (analiza wrazliwosci)."""
    d = G12_DZIEN if dzien is None else dzien
    n = G12_NOC if noc is None else noc
    return n if h in godziny_nocne(sezon) else d


def rdn_detal(cena_hurt_mwh, narzut=None):
    """Cena detaliczna taryfy dynamicznej z hurtowej ceny RDN [zl/MWh]."""
    nar = NARZUT if narzut is None else narzut
    return (cena_hurt_mwh / 1000.0 + nar) * VAT


def prog_udzialu_nocnego(g11=None, dzien=None, noc=None):
    """
    Udzial zuzycia w strefie nocnej, powyzej ktorego G12 jest tansza od G11.

        tau = (c_dzien - c_G11) / (c_dzien - c_noc)          [= 23,50 %]

    Wyprowadzenie: G12 tansza  <=>  u*c_noc + (1-u)*c_dzien < c_G11.
    """
    c11 = G11 if g11 is None else g11
    d = G12_DZIEN if dzien is None else dzien
    n = G12_NOC if noc is None else noc
    if d == n:
        return float("nan")
    return (d - c11) / (d - n)


def prog_ceny_hurtowej(g11=None, narzut=None):
    """Cena hurtowa [zl/MWh], ponizej ktorej RDN jest tansza od G11."""
    c11 = G11 if g11 is None else g11
    nar = NARZUT if narzut is None else narzut
    return (c11 / VAT - nar) * 1000.0


def udzial_nocny(E_h, sezon):
    """Udzial zuzycia przypadajacy na strefe tansza G12 dla profilu E_h[24]."""
    s = sum(E_h)
    if s <= 0:
        return 0.0
    nh = godziny_nocne(sezon)
    return sum(E_h[h] for h in range(24) if h in nh) / s


# --------------------------------------------------------------- kontrole
assert len(godziny_nocne("Zima")) == 10, "strefa tansza G12 musi miec 10 godzin"
assert len(godziny_nocne("Lato")) == 10, "strefa tansza G12 musi miec 10 godzin"
assert not (NOC_CALODOBOWA & OKNO_LETNIE), "okna stref nie moga sie nakladac"
assert not (NOC_CALODOBOWA & OKNO_ZIMOWE), "okna stref nie moga sie nakladac"
assert abs(G11 - 1.0991) < 5e-4, "G11 rozjechalo sie ze skladnikami taryfy"
assert abs(G12_DZIEN - 1.2491) < 5e-4, "G12 dzien rozjechalo sie ze skladnikami"
assert abs(G12_NOC - 0.6111) < 5e-4, "G12 noc rozjechalo sie ze skladnikami"
