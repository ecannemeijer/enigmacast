<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Features';
$active = 'features';
require __DIR__ . '/includes/header.php';
?>
<h1>Features</h1>
<p class="lead">The receiver keeps the GUI. EnigmaCast plays the picture and forwards the remote.</p>

<section class="panel">
    <h2>Live picture</h2>
    <p>The TV pulls the MPEG-TS stream from port 8001. Media3 / ExoPlayer plays it with the TV's own decoders: MPEG-2, H.264, H.265, AAC, AC3, and E-AC3, when the hardware exposes those formats.</p>
</section>
<section class="panel">
    <h2>Real OSD</h2>
    <p>OpenWebif serves a grab of the Enigma2 screen (<code>/grab</code>, PNG or JPG, mode osd, all, or video). That image sits on top of the video. An unchanged frame is not drawn again. After a key press the app polls in a short burst, then slows down.</p>
</section>
<section class="panel">
    <h2>Remote control</h2>
    <p>Arrows, OK, colour keys, and the rest go to <code>/api/remotecontrol</code> as Linux input codes. Back sends EXIT and does not close the app. Home stays Home. Volume stays with Android, because the audio is decoded on the TV.</p>
</section>
<section class="panel">
    <h2>Follow the channel</h2>
    <p>The app reads <code>/api/getcurrent</code>. When the receiver changes channel, the stream URL changes with it. An OSD problem does not stop the video, and a stream error does not stop the remote.</p>
</section>
<section class="panel">
    <h2>Connection</h2>
    <p>On first launch you enter a name, host, ports, and an optional OpenWebif login. The test calls <code>/api/about</code>, probes the grab, and checks that the stream port accepts a TCP connection. Search on the network uses mDNS and fills in a host when something answers.</p>
</section>
<section class="panel">
    <h2>Help and ideas</h2>
    <p>The <a href="<?= e(url('forum/')) ?>">forum</a> has two boards: Help for questions, and Feature request for wishes.</p>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
