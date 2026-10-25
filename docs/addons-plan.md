# Plan: EnigmaCast Addons en Torrent als aparte APK

- **Status:** plan only — nog geen branch, code of APK gewijzigd.
- **Datum:** 23-10-2026
- **Beoogde branch:** `feature/addons`
- **Uitgangspunt:** EnigmaCast TV, actuele featurebranch `feature/channel-equivalents`
- **Doel:** de hoofd-APK kleiner maken en Torrent als losse, later uitbreidbare addon laden.

## Kort antwoord

Dit kan, maar niet door Torrent-code rechtstreeks uit dezelfde APK te laden. De veilige en onderhoudbare route is:

- Torrent wordt een **aparte APK** met een eigen Activity, UI en speler.
- De EnigmaCast-hoofdapp krijgt een **Addons-manager** die addons ontdekt, controleert en start.
- Favorieten, verder-kijken en synchronisatie blijven in de **hoofdapp-database**. De addon schrijft daarin via een kleine, beveiligde **AIDL-koppeling**.

Zo blijft het gedrag van Torrent gelijk, maar verdwijnen de Torrent-engine, libtorrent-native libraries en Torrent-UI uit de hoofd-APK.

## Waarom geen plugin-code in dezelfde APK

| Optie | Geschikt? |
|---|---|
| Aparte APK met Activity + AIDL | **Ja, aanbevolen** |
| Dynamic Feature Module | Nee: geen losse sideload-APK en geen vrije addonkeuze |
| `DexClassLoader` / losse dex | Nee: fragiel met Compose, resources, Hilt en thema's |
| WebView/remote addon | Nee: geen echte Torrent-integratie |

Een APK mag niet zomaar elkaars code of database delen. Een **Activity-grens** en **AIDL-contract** zijn de stabiele Android-manier.

## Architectuur

```text
EnigmaCast host-APK
├── AddonManager
│   ├── ontdekt addon-APK's
│   ├── controleert handtekening/versie
│   └── start de addon-Activity
├── AddonHostService (AIDL)
│   ├── favorieten
│   ├── verder-kijken
│   ├── bibliotheek
│   ├── profiel
│   └── premium/feature-check
├── Settings › Addons
└── My Items / Verder kijken
        ▲
        │ AIDL
        ▼
Torrent-addon-APK
├── TorrentAddonActivity (Compose)
├── Torrent-speler (ExoPlayer)
├── Torrent-engine + libtorrent
├── Stremio-manifest/instellingen
└── bindt aan AddonHostService
```

De hoofdapp bevat daarna **geen** Torrent-engine, Torrent-UI, Stremio-parser of libtorrent meer.

## Nieuwe Gradle-modules

- `:app` — EnigmaCast hoofdapp
- `:addon-api` — kleine Android-library met:
  - AIDL-interfaces
  - Parcelable modellen
  - addon-ID's, API-versie, permissions
- `:addon-torrent` — aparte application-module:
  - `applicationId com.enigmacast.addon.torrent`
  - TorrentActivity, TorrentViewModel, TorrentScreen, engine, speler
  - jlibtorrent-afhankelijkheden
- optioneel `:design` / `:player-ui` — alleen als thema en speler-OSD gedeeld moeten worden zonder alles te dupliceren

De addon-APK krijgt een eigen release en eigen versienummer.

## AIDL-contract

### Addon → host

`IEnigmaCastAddon`:

- `AddonInfo info()` — id, naam, versie, API-versie, capabilities
- `Intent launchIntent()` — Activity om te openen
- `List<AddonSetting> settings()` — bijvoorbeeld de Stremio-manifest-URL
- `void applySetting(key, value)`
- `boolean isCompatible(hostApiVersion)`

### Host → addon

`IAddonHost`:

- `String profileId()`
- `boolean isFeatureAllowed(String feature)`
- `List<LibraryRow> favorites(String source)`
- `List<ResumeRow> resumes(String source)`
- `void setFavorite(LibraryRow row, boolean favorite)`
- `ResumeRow resume(source, kind, id)`
- `void saveResume(ResumeRow row)`
- `void clearResume(source, kind, id)`
- `void requestLibrarySync()`

De addon gebruikt `source = "torrent"`, precies zoals de huidige bibliotheekregels.

## Favorieten en Verder kijken

Nu schrijft `TorrentLibrary` rechtstreeks in de host-`IptvLibrary`, met bron `"torrent"`. Dat blijft zo:

1. De addon opent een film of aflevering.
2. De addon vraagt via AIDL aan de host om de favoriet/positie op te slaan.
3. De host schrijft in de bestaande user-database.
4. My Items, Verder kijken, Watch Next en de sync werken zoals nu.
5. Bij een klik op een Torrent-kaart opent de host de addon-Activity met `source`, `kind`, `id` en eventueel `episode`.

Daardoor blijven favorieten en verder-kijken **in de hoofdapp** en gaan ze niet verloren bij een addon-update of herinstallatie.

De host moet daarvoor:

- Torrent-specifieke bibliotheekcode vervangen door generieke `LibraryRow`-logica;
- `TorrentLibraryRows` vervangen door een gedeelde bronconstante in `:addon-api`;
- de Companion-bridge niet langer Torrent-regels laten wegfilteren;
- een generieke "open addon voor deze bibliotheekkaart"-route krijgen.

## Settings › Addons

Nieuwe pagina `Settings › Addons`:

- lijst met geïnstalleerde addons;
- status: geïnstalleerd / uit / versie / compatibel;
- aan/uit-schakelaar;
- **Torrent**-kaart met:
  - openen;
  - Stremio-manifest-URL;
  - valideren;
  - versie en update-status;
- installatie/update van een addon-APK via een catalogus-URL of sideload;
- "Addon niet geïnstalleerd" met installatie-instructie.

### Belangrijk onderscheid

Er zijn straks twee verschillende "manifests":

1. **Addon-manifest/catalogus** — welke addon-APK's bestaan en waar ze te downloaden zijn.
2. **Torrent/Stremio-manifest** — de bestaande scraper-URL die nu in Settings › Scrapers staat.

De Stremio-manifest-URL verhuist naar **Addons › Torrent**. De addon-catalogus is alleen voor het installeren en updaten van addon-APK's.

## Addon ontdekken en beveiligen

- Addon-APK's declareren een service met actie `com.enigmacast.addon.BIND`.
- De host gebruikt `<queries>` in het manifest om ze te vinden.
- Alleen addons met de juiste **handtekening** worden geaccepteerd.
- Service wordt beschermd met een signature-level permission:
  - `com.enigmacast.permission.ADDON`
- De host controleert:
  - API-versie;
  - minimale hostversie;
  - packagenaam;
  - handtekening;
  - of de addon enabled is.
- Geen `sharedUserId`, geen wereldwijd leesbare database, geen ongecontroleerde exported provider.

Voor nu: de Torrent-addon wordt met dezelfde EnigmaCast-releasekey ondertekend. Voor toekomstige externe addons kan later een publieke sleutel-allowlist komen.

## Wat er uit de hoofdapp verdwijnt

Verplaatsen naar de addon:

- `torrent/TorrentEngine.kt`
- `torrent/TorrentHttpServer.kt`
- `torrent/TorrentNative.kt`
- `torrent/TorrentStreamingService.kt`
- `data/torrent/ScraperRepository.kt`
- `data/torrent/TorrentLibrary.kt`
- `domain/torrent/*`
- `ui/torrent/*`
- de vier jlibtorrent-ABI-afhankelijkheden:
  - arm
  - arm64
  - x64
  - x86

Dat is naar verwachting de grootste APK-besparing, vooral door de native libtorrent-bibliotheken.

In de host blijft alleen:

- de generieke Addons-manager;
- het AIDL-contract;
- de generieke bibliotheekregels;
- de Addons-instellingen;
- de knop/het menu om de addon te openen.

## Migratie van bestaande gebruikers

Bij de eerste update:

- bestaande Torrent-favorieten en verder-kijken blijven staan, want die rijen hebben al bron `"torrent"`;
- de bestaande Stremio-manifest-URL wordt uit de host-secure-store gelezen en eenmalig aan de addon doorgegeven;
- de addon slaat hem daarna zelf versleuteld op;
- bestaande Torrent-voorkeuren en filters worden waar nodig overgezet;
- bestaande Watch Next- en syncregels blijven werken;
- als de addon nog niet is geïnstalleerd, blijven de bibliotheekrijen zichtbaar maar tonen een "Addon installeren"-melding.

## Fases

| Fase | Werk |
|---|---|
| 0 | Branch `feature/addons` maken vanaf de actuele feature-branch |
| 1 | `:addon-api` maken: AIDL, parcelables, versie, permissions |
| 2 | Host: `AddonManager`, `AddonHostService`, Addons-pagina, addon-detectie |
| 3 | Torrent-module verplaatsen naar `:addon-torrent` en activity/service bouwen |
| 4 | Favorieten/verder-kijken via AIDL laten verlopen; hostbibliotheek generiek maken |
| 5 | Torrent-tile, zoeken, Watch Next en Companion koppelen |
| 6 | Migratie van manifest en bestaande bibliotheekgegevens |
| 7 | Testen, APK-grootte meten, releases bouwen |

## Testmatrix

- Host zonder addon: geen Torrent-tile, Addons toont "installeren".
- Addon geïnstalleerd: ontdekken, openen, Torrent werkt als nu.
- Verkeerde handtekening: addon wordt geweigerd.
- Oude/nieuwe API-versie: duidelijke melding.
- Favoriet toevoegen/verwijderen: zichtbaar in My Items.
- Verder kijken: positie wordt opgeslagen en hervat.
- Watch Next en sync: geen regressie.
- Profielwissel: juiste profielgebonden rijen.
- Free/Premium: host blijft de toegang bewaken.
- Addon verwijderen: host blijft stabiel.
- Addon-update: bibliotheek en manifest blijven behouden.
- Companion: Torrent-bibliotheekregels blijven beschikbaar.
- Grootte: host-APK vóór/na meten.

## Releasevorm

Na deze wijziging zijn er drie APK's:

- `enigmacast.apk`
- `enigmacast-companion.apk`
- `enigmacast-addon-torrent.apk`

De update-JSON krijgt een extra `addons`-blok met versie, URL en SHA-256 van de Torrent-addon.

## Belangrijke beslissingen vóór uitvoering

1. **Addon-UI als aparte Activity** — aanbevolen, eenvoudig en veilig.
2. **Favorieten/verder-kijken in de host-database** — aanbevolen, anders raak je sync en Watch Next kwijt.
3. **Stremio-manifest in de addon opslaan**, met de host-Addons-pagina als invoer/validator.
4. **Zelfde releasekey voor host en Torrent-addon**; externe addons later via allowlist.
5. **Addon installeren via catalogus of sideload**; beide kunnen, maar catalogus is beter voor updates.
6. **Companion moet Torrent-regels gaan tonen**; nu filtert de bridge ze weg.
7. **Bij ontbrekende addon:** Torrent-tile verbergen en in My Items een installatiehint tonen.

## Uitvoeringsstatus

Nog niet gestart. Geen branch aangemaakt, geen code gewijzigd, geen APK gebouwd. Wachten op expliciet akkoord om met dit plan verder te gaan.
