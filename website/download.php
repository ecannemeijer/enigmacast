<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$release = release_info();
$pageTitle = 'Download';
$active = 'download';
require __DIR__ . '/includes/header.php';

$version = (string) ($release['versionName'] ?? 'unknown');
$code = $release['versionCode'] ?? null;
$apk = (string) ($release['apkUrl'] ?? 'https://update.velixatv.com/enigmacast.apk');
$notes = trim((string) ($release['notes'] ?? ''));
?>
<h1>Download the APK</h1>
<p class="lead">Install EnigmaCast on an Android TV. Sideload the APK with a file manager or <code>adb install</code>.</p>
<section class="panel">
    <p><span class="badge">version</span> <?= e($version) ?></p>
    <?php if ($code !== null && $code !== ''): ?>
        <p><span class="badge">versionCode</span> <?= e((string) $code) ?></p>
    <?php endif; ?>
    <?php if ($notes !== ''): ?>
        <h2>This build</h2>
        <p><?= e($notes) ?></p>
    <?php endif; ?>
    <div class="actions">
        <a class="btn" href="<?= e($apk) ?>">Download APK</a>
    </div>
    <p class="meta">File: <?= e($apk) ?></p>
</section>
<section class="panel">
    <h2>After that</h2>
    <p>Turn on OpenWebif, start the app, and follow the <a href="<?= e(url('guide.php')) ?>">guide</a>. If something does not work, post it on the <a href="<?= e(url('forum/?cat=help')) ?>">help board</a>.</p>
</section>
<?php require __DIR__ . '/includes/footer.php'; ?>
