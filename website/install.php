<?php

declare(strict_types=1);

require __DIR__ . '/bootstrap.php';

$cfg = config();
$done = false;
$error = null;
$already = false;

try {
    $server = new PDO(
        sprintf('mysql:host=%s;charset=utf8mb4', $cfg['db_host']),
        $cfg['db_user'],
        $cfg['db_pass'],
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
    $server->exec(
        'CREATE DATABASE IF NOT EXISTS `' . str_replace('`', '', $cfg['db_name'])
        . '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
    );
    $server->exec('USE `' . str_replace('`', '', $cfg['db_name']) . '`');

    $table = $server->query("SHOW TABLES LIKE 'categories'")->fetch();
    if ($table !== false) {
        $already = true;
    }

    if (!$already) {
        $sql = (string) file_get_contents(__DIR__ . '/sql/schema.sql');
        $parts = preg_split('/;\s*(?:\R|$)/', $sql) ?: [];
        foreach ($parts as $statement) {
            $statement = trim($statement);
            if ($statement === '') {
                continue;
            }
            $server->exec($statement);
        }
        $done = true;
    }
} catch (PDOException $exception) {
    $error = $exception->getMessage();
}

$pageTitle = 'Installeren';
$active = '';
require __DIR__ . '/includes/header.php';
?>
<h1>Database installeren</h1>
<?php if ($error !== null): ?>
    <section class="panel">
        <p class="error">MySQL is niet bereikbaar of het schema liep vast.</p>
        <p><?= e($error) ?></p>
        <p>Start MySQL in XAMPP. De site verwacht gebruiker <code>root</code> zonder wachtwoord op <code>127.0.0.1</code>, database <code>enigmacast</code>. Pas <code>website/config.php</code> aan als dat bij jou anders is.</p>
    </section>
<?php elseif ($already): ?>
    <section class="panel">
        <p>De tabellen bestaan al. Installeren is daarom gestopt, zodat bestaande berichten blijven staan.</p>
        <p><a class="btn" href="<?= e(url()) ?>">Naar de site</a></p>
    </section>
<?php elseif ($done): ?>
    <section class="panel">
        <p>Database <code>enigmacast</code> staat klaar, met de borden Hulp en Feature request.</p>
        <p><a class="btn" href="<?= e(url('forum/')) ?>">Naar het forum</a></p>
    </section>
<?php endif; ?>
<?php require __DIR__ . '/includes/footer.php'; ?>
