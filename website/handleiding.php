<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Handleiding';
$active = 'handleiding';
require __DIR__ . '/includes/header.php';
?>
<h1>Handleiding</h1>

<section class="panel">
    <h2>Wat je nodig hebt</h2>
    <ul class="plain">
        <li>Android TV of emulator, Android 9 of nieuwer (minSdk 28).</li>
        <li>Enigma2-ontvanger in hetzelfde netwerk, met OpenWebif aan.</li>
        <li>Stream-server op poort 8001, tenzij het image een andere poort gebruikt.</li>
    </ul>
</section>

<section class="panel">
    <h2>Eerste koppeling</h2>
    <ol class="plain">
        <li>Zet OpenWebif aan op de ontvanger (OpenATV: menu, plugins, OpenWebif).</li>
        <li>Noteer het IP-adres, de HTTP-poort (meestal 80) en of er een gebruikersnaam en wachtwoord op staan.</li>
        <li>Laat de stream-server op poort 8001, tenzij je image iets anders gebruikt.</li>
        <li>Start EnigmaCast. De eerste keer opent het verbindingsscherm.</li>
        <li>Vul naam, host, poorten en optioneel de inloggegevens in.</li>
        <li>Test de verbinding en kies daarna Verbinden.</li>
    </ol>
    <p>Een geslaagde test toont het model, het image en de Enigma-versie uit OpenWebif. HTTP mag, omdat OpenWebif thuis vaak geen versleuteling heeft. Het scherm waarschuwt daarvoor. HTTPS gebruikt de certificaten van het systeem. Een zelfondertekend certificaat vertrouw je pas nadat je dat per ontvanger aanzet.</p>
</section>

<section class="panel">
    <h2>Veelvoorkomende problemen</h2>
    <h3>Test faalt meteen</h3>
    <p>Controleer het IP-adres, of OpenWebif aan staat en of de tv in hetzelfde LAN zit. Een VPN op de tv verbergt de ontvanger.</p>
    <h3>HTTP 401</h3>
    <p>Vul de OpenWebif-gebruikersnaam en het wachtwoord in.</p>
    <h3>HTTPS-certificaat</h3>
    <p>Zet de optie voor een zelfondertekend certificaat alleen aan voor die ontvanger, of installeer een certificaat dat de tv al vertrouwt.</p>
    <h3>Beeld zonder OSD</h3>
    <p>Open de OSD-diagnose en probeer eerst PNG OSD, daarna JPG OSD. Controleer in een browser of <code>/grab</code> op dezelfde URL een plaatje geeft.</p>
    <h3>OSD zonder beeld</h3>
    <p>Poort 8001 moet open zijn en de ontvanger mag niet in standby staan. Met automatisch opnieuw verbinden pakt de speler de stream zelf weer op.</p>
    <h3>Zappen komt later op de tv</h3>
    <p>Die vertraging is de zendercontrole plus het opstarten van tuner en decoder. Zap je met de Android-afstandsbediening, dan pollt de app sneller.</p>
</section>

<section class="panel">
    <h2>Bekende grenzen</h2>
    <ul class="plain">
        <li>OpenWebif kan geen losse key-up of kernel-repeat sturen. Ingedrukt houden is een reeks korte commando’s.</li>
        <li>Doorzichtigheid van een PNG-OSD hangt af van het image en van <code>grab</code>. Op sommige boxen is alles buiten de widgets dekkend zwart.</li>
        <li>AC3, E-AC3 en MPEG-2 hangen af van de decoder in de Android TV.</li>
        <li>Zoeken via mDNS is een poging, geen garantie.</li>
    </ul>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
