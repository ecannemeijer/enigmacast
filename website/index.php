<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Home';
$active = 'home';
require __DIR__ . '/includes/header.php';
?>
<h1>Android TV als scherm voor Enigma2</h1>
<p class="lead">EnigmaCast laat een Android TV het beeld van een Enigma2-ontvanger afspelen. De ontvanger blijft de bediening. De tv toont de live MPEG-TS-stream en legt de echte Enigma2-OSD eroverheen.</p>
<div class="actions">
    <a class="btn" href="<?= e(url('download.php')) ?>">APK downloaden</a>
    <a class="btn" href="<?= e(url('forum/')) ?>">Forum</a>
</div>
<pre class="diagram">Enigma2-ontvanger
    live MPEG-TS :8001 ------------&gt; Media3 / ExoPlayer --+
    /grab?format=png&amp;mode=osd -----&gt; OSD-overlay ---------+--&gt; Android TV
    /api/remotecontrol &lt;------------ afstandsbediening ---+
    /api/getcurrent ---------------&gt; zender volgen -------+</pre>
<div class="grid">
    <section class="panel">
        <h2>Voor wie</h2>
        <p>Eerste doel is een Octagon SF8008 met OpenATV. Niets in de app zit vast aan dat model. Dezelfde OpenWebif-client werkt op andere Enigma2-ontvangers (Vu+, Dreambox, Zgemma, Mutant, Gigablue, Edision en meer) zolang OpenWebif en de stream-poort bereikbaar zijn.</p>
    </section>
    <section class="panel">
        <h2>Wat de tv niet nabouwt</h2>
        <p>Infobalk, zenderlijst, EPG, menu’s, timers en plugins blijven op de ontvanger. Ze verschijnen via de OSD-grab. De Android-afstandsbediening stuurt Linux-toetscodes naar OpenWebif.</p>
    </section>
</div>
<?php require __DIR__ . '/includes/footer.php'; ?>
