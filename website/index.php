<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Home';
$active = 'home';
require __DIR__ . '/includes/header.php';
?>
<h1>Android TV as a screen for Enigma2</h1>
<p class="lead">EnigmaCast turns an Android TV into a remote screen for an Enigma2 receiver. The receiver stays in charge. The TV plays the live MPEG-TS stream and draws the real Enigma2 OSD on top of it.</p>
<div class="actions">
    <a class="btn" href="<?= e(url('download.php')) ?>">Download APK</a>
    <a class="btn" href="<?= e(url('forum/')) ?>">Forum</a>
</div>
<pre class="diagram">Enigma2 receiver
    live MPEG-TS :8001 ------------&gt; Media3 / ExoPlayer --+
    /grab?format=png&amp;mode=osd -----&gt; OSD overlay ---------+--&gt; Android TV
    /api/remotecontrol &lt;------------ remote keys ---------+
    /api/getcurrent ---------------&gt; service monitor -----+</pre>
<div class="grid">
    <section class="panel">
        <h2>Who it is for</h2>
        <p>The first target is an Octagon SF8008 running OpenATV. Nothing in the app is hardcoded to that model. The same OpenWebif client works with other Enigma2 receivers (Vu+, Dreambox, Zgemma, Mutant, Gigablue, Edision, and others) as long as they expose OpenWebif and the streaming port.</p>
    </section>
    <section class="panel">
        <h2>What the TV does not rebuild</h2>
        <p>The infobar, channel list, EPG, menus, timers, and plugins stay on the receiver. They show up through the OSD grab. The Android TV remote sends Linux key codes to OpenWebif.</p>
    </section>
</div>
<?php require __DIR__ . '/includes/footer.php'; ?>
