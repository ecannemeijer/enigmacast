<?php

declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';

if (current_user() !== null) {
    header('Location: ' . url('forum/'));
    exit;
}

$error = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $username = trim((string) ($_POST['username'] ?? ''));
    $password = (string) ($_POST['password'] ?? '');
    $again = (string) ($_POST['password_again'] ?? '');
    if (!preg_match('/^[A-Za-z0-9_]{3,24}$/', $username)) {
        $error = 'Gebruikersnaam: 3 tot 24 tekens, letters, cijfers of _.';
    } elseif (strlen($password) < 8) {
        $error = 'Kies een wachtwoord van minstens 8 tekens.';
    } elseif (!hash_equals($password, $again)) {
        $error = 'De wachtwoorden komen niet overeen.';
    } else {
        try {
            $stmt = db()->prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)');
            $stmt->execute([$username, password_hash($password, PASSWORD_DEFAULT)]);
            $_SESSION['user_id'] = (int) db()->lastInsertId();
            $_SESSION['flash'] = 'Account aangemaakt.';
            header('Location: ' . url('forum/'));
            exit;
        } catch (PDOException $exception) {
            if ((int) ($exception->errorInfo[1] ?? 0) === 1062) {
                $error = 'Die gebruikersnaam bestaat al.';
            } else {
                $error = 'Account aanmaken lukt nu niet. Is de database geïnstalleerd?';
            }
        }
    }
}

$pageTitle = 'Account';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<h1>Account maken</h1>
<form class="panel" method="post" action="<?= e(url('forum/register.php')) ?>">
    <?= csrf_field() ?>
    <?php if ($error !== null): ?>
        <p class="error"><?= e($error) ?></p>
    <?php endif; ?>
    <label for="username">Gebruikersnaam</label>
    <input id="username" name="username" required maxlength="24" autocomplete="username" value="<?= e((string) ($_POST['username'] ?? '')) ?>">
    <label for="password">Wachtwoord</label>
    <input id="password" name="password" type="password" required minlength="8" autocomplete="new-password">
    <label for="password_again">Wachtwoord nog eens</label>
    <input id="password_again" name="password_again" type="password" required minlength="8" autocomplete="new-password">
    <div class="actions"><button class="btn" type="submit">Aanmaken</button></div>
    <p>Al een account? <a href="<?= e(url('forum/login.php')) ?>">Inloggen</a></p>
</form>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
