<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Functies';
$active = 'functies';
require __DIR__ . '/includes/header.php';
?>
<h1>Functies</h1>
<p class="lead">De ontvanger blijft de GUI. EnigmaCast speelt het beeld af en stuurt de afstandsbediening door.</p>

<section class="panel">
    <h2>Live beeld</h2>
    <p>De tv haalt de MPEG-TS-stream op via poort 8001. Media3 / ExoPlayer speelt die af met de decoders van de tv: MPEG-2, H.264, H.265, AAC, AC3 en E-AC3, als de hardware die formaten aanbiedt.</p>
</section>
<section class="panel">
    <h2>Echte OSD</h2>
    <p>OpenWebif levert een grab van het Enigma2-beeld (<code>/grab</code>, PNG of JPG, modus osd, all of video). Die afbeelding ligt over de video. Een ongewijzigd beeld wordt niet opnieuw getekend. Na een toetsaanslag pollt de app kort achter elkaar, daarna langzamer.</p>
</section>
<section class="panel">
    <h2>Afstandsbediening</h2>
    <p>Pijltjes, OK, kleurtoetsen en de rest gaan als Linux-inputcodes naar <code>/api/remotecontrol</code>. Back stuurt EXIT en sluit de app niet. Home blijft Home. Volume blijft bij Android, omdat het geluid op de tv wordt gedecodeerd.</p>
</section>
<section class="panel">
    <h2>Zender volgen</h2>
    <p>De app leest <code>/api/getcurrent</code>. Wisselt de ontvanger van zender, dan schakelt de stream-URL mee. OSD-problemen stoppen het beeld niet, en een streamfout stopt de afstandsbediening niet.</p>
</section>
<section class="panel">
    <h2>Verbinding</h2>
    <p>Bij de eerste start vul je naam, host, poorten en eventueel een OpenWebif-gebruiker in. De test roept <code>/api/about</code> aan, probeert de grab en controleert of de stream-poort een TCP-verbinding aanneemt. Zoeken op het netwerk gebruikt mDNS en vult een host in als iets antwoordt.</p>
</section>
<section class="panel">
    <h2>Hulp en ideeën</h2>
    <p>Op het <a href="<?= e(url('forum/')) ?>">forum</a> staan twee borden: Hulp voor vragen, en Feature request voor wensen.</p>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
