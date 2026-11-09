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
        $server->exec("UPDATE categories SET slug = 'help', name = 'Help' WHERE slug = 'hulp'");
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

$pageTitle = 'Install';
$active = '';
require __DIR__ . '/includes/header.php';
?>
<h1>Install the database</h1>
<?php if ($error !== null): ?>
    <section class="panel">
        <p class="error">MySQL is not reachable, or the schema failed.</p>
        <p><?= e($error) ?></p>
        <p>Start MySQL in XAMPP. The site expects user <code>root</code> with an empty password on <code>127.0.0.1</code>, database <code>enigmacast</code>. Change <code>website/config.php</code> if yours is different.</p>
    </section>
<?php elseif ($already): ?>
    <section class="panel">
        <p>The tables already exist. Install stopped so existing posts stay in place.</p>
        <p><a class="btn" href="<?= e(url()) ?>">Go to the site</a></p>
    </section>
<?php elseif ($done): ?>
    <section class="panel">
        <p>Database <code>enigmacast</code> is ready, with the Help and Feature request boards.</p>
        <p><a class="btn" href="<?= e(url('forum/')) ?>">Go to the forum</a></p>
    </section>
<?php endif; ?>
<?php require __DIR__ . '/includes/footer.php'; ?>
