<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$release = release_info();
$pageTitle = 'Download';
$active = 'download';
require __DIR__ . '/includes/header.php';

$version = (string) ($release['versionName'] ?? 'onbekend');
$code = $release['versionCode'] ?? null;
$apk = (string) ($release['apkUrl'] ?? 'https://update.velixatv.com/enigmacast.apk');
$notes = trim((string) ($release['notes'] ?? ''));
?>
<h1>APK downloaden</h1>
<p class="lead">Installeer EnigmaCast op een Android TV. Sideload de APK via een bestandbeheerder of <code>adb install</code>.</p>
<section class="panel">
    <p><span class="badge">versie</span> <?= e($version) ?></p>
    <?php if ($code !== null && $code !== ''): ?>
        <p><span class="badge">versionCode</span> <?= e((string) $code) ?></p>
    <?php endif; ?>
    <?php if ($notes !== ''): ?>
        <h2>Deze build</h2>
        <p><?= e($notes) ?></p>
    <?php endif; ?>
    <div class="actions">
        <a class="btn" href="<?= e($apk) ?>">Download APK</a>
    </div>
    <p class="meta">Bestand: <?= e($apk) ?></p>
</section>
<section class="panel">
    <h2>Daarna</h2>
    <p>Zet OpenWebif aan, start de app en volg de <a href="<?= e(url('handleiding.php')) ?>">handleiding</a>. Werkt er iets niet, zet het op het <a href="<?= e(url('forum/?cat=hulp')) ?>">hulpforum</a>.</p>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
