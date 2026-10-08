<?php

declare(strict_types=1);

$pageTitle = $pageTitle ?? 'EnigmaCast';
$active = $active ?? '';
$user = current_user();
$notice = flash();

$nav = [
    'home' => ['Home', url()],
    'functies' => ['Functies', url('functies.php')],
    'handleiding' => ['Handleiding', url('handleiding.php')],
    'download' => ['Download', url('download.php')],
    'forum' => ['Forum', url('forum/')],
];
?>
<!DOCTYPE html>
<html lang="nl">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= e($pageTitle) ?> — EnigmaCast</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Press+Start+2P&family=VT323&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="<?= e(url('assets/style.css')) ?>">
</head>
<body>
<div class="scanlines" aria-hidden="true"></div>
<header class="site-header">
    <a class="logo" href="<?= e(url()) ?>">EnigmaCast</a>
    <nav class="nav">
        <?php foreach ($nav as $key => [$label, $href]): ?>
            <a class="<?= $active === $key ? 'is-active' : '' ?>" href="<?= e($href) ?>"><?= e($label) ?></a>
        <?php endforeach; ?>
    </nav>
    <p class="userbar">
        <?php if ($user): ?>
            <span><?= e($user['username']) ?></span>
            <a href="<?= e(url('forum/logout.php')) ?>">Uitloggen</a>
        <?php else: ?>
            <a href="<?= e(url('forum/login.php')) ?>">Inloggen</a>
            <a href="<?= e(url('forum/register.php')) ?>">Account</a>
        <?php endif; ?>
    </p>
</header>
<main class="wrap">
<?php if ($notice !== null): ?>
    <p class="flash" role="status"><?= e($notice) ?></p>
<?php endif; ?>
