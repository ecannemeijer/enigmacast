<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$pageTitle = 'Guide';
$active = 'guide';
require __DIR__ . '/includes/header.php';
?>
<h1>Guide</h1>

<section class="panel">
    <h2>What you need</h2>
    <ul class="plain">
        <li>An Android TV or emulator, Android 9 or newer (minSdk 28).</li>
        <li>An Enigma2 receiver on the same network, with OpenWebif enabled.</li>
        <li>The stream server on port 8001, unless the image uses another port.</li>
    </ul>
</section>

<section class="panel">
    <h2>First connection</h2>
    <ol class="plain">
        <li>Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).</li>
        <li>Note the IP address, the HTTP port (usually 80), and whether a username and password are set.</li>
        <li>Leave the stream server on port 8001, unless your image uses another port.</li>
        <li>Start EnigmaCast. The first launch opens the connection screen.</li>
        <li>Enter the name, host, ports, and optional credentials.</li>
        <li>Test the connection, then choose Connect.</li>
    </ol>
    <p>A successful test shows the model, image, and Enigma version returned by OpenWebif. HTTP is allowed, because OpenWebif at home is often plain HTTP. The screen warns about that. HTTPS uses the system trust store. Trusting a self-signed certificate stays off until you enable it for that receiver.</p>
</section>

<section class="panel">
    <h2>Common problems</h2>
    <h3>The test fails immediately</h3>
    <p>Check the IP address, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN on the TV hides the receiver.</p>
    <h3>HTTP 401</h3>
    <p>Fill in the OpenWebif username and password.</p>
    <h3>HTTPS certificate</h3>
    <p>Enable the self-signed option only for that receiver, or install a certificate the TV already trusts.</p>
    <h3>Picture without OSD</h3>
    <p>Open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm in a browser that <code>/grab</code> on the same URL returns an image.</p>
    <h3>OSD without picture</h3>
    <p>Port 8001 must be open and the receiver must not be in standby. With auto-reconnect the player picks the stream up again on its own.</p>
    <h3>Zapping shows up late on the TV</h3>
    <p>That delay is the service poll plus tuner and decoder startup. Zapping from the Android remote triggers the fast poll.</p>
</section>

<section class="panel">
    <h2>Known limits</h2>
    <ul class="plain">
        <li>OpenWebif cannot send a bare key-up or a kernel auto-repeat. Holding a key is a series of short commands.</li>
        <li>PNG OSD transparency depends on the image and on <code>grab</code>. On some boxes everything outside the widgets is opaque black.</li>
        <li>AC3, E-AC3, and MPEG-2 depend on the decoder in the Android TV.</li>
        <li>mDNS discovery is best-effort.</li>
    </ul>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
