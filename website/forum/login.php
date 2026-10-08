<?php

declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';

$next = safe_next((string) ($_GET['next'] ?? $_POST['next'] ?? ''));

if (current_user() !== null) {
    header('Location: ' . $next);
    exit;
}

$error = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $username = trim((string) ($_POST['username'] ?? ''));
    $password = (string) ($_POST['password'] ?? '');
    try {
        $stmt = db()->prepare('SELECT id, password_hash FROM users WHERE username = ?');
        $stmt->execute([$username]);
        $row = $stmt->fetch();
        if ($row !== false && password_verify($password, $row['password_hash'])) {
            session_regenerate_id(true);
            $_SESSION['user_id'] = (int) $row['id'];
            $_SESSION['flash'] = 'Je bent ingelogd.';
            header('Location: ' . $next);
            exit;
        }
        $error = 'Gebruikersnaam of wachtwoord klopt niet.';
    } catch (PDOException) {
        $error = 'Inloggen lukt nu niet. Is de database geïnstalleerd?';
    }
}

$pageTitle = 'Inloggen';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<h1>Inloggen</h1>
<form class="panel" method="post" action="<?= e(url('forum/login.php')) ?>">
    <?= csrf_field() ?>
    <input type="hidden" name="next" value="<?= e($next) ?>">
    <?php if ($error !== null): ?>
        <p class="error"><?= e($error) ?></p>
    <?php endif; ?>
    <label for="username">Gebruikersnaam</label>
    <input id="username" name="username" required maxlength="24" autocomplete="username" value="<?= e((string) ($_POST['username'] ?? '')) ?>">
    <label for="password">Wachtwoord</label>
    <input id="password" name="password" type="password" required autocomplete="current-password">
    <div class="actions"><button class="btn" type="submit">Inloggen</button></div>
    <p>Nog geen account? <a href="<?= e(url('forum/register.php')) ?>">Account maken</a></p>
</form>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
