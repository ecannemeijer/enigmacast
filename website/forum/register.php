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
        $error = 'Username: 3 to 24 characters, letters, digits, or _.';
    } elseif (strlen($password) < 8) {
        $error = 'Choose a password of at least 8 characters.';
    } elseif (!hash_equals($password, $again)) {
        $error = 'The passwords do not match.';
    } else {
        try {
            $stmt = db()->prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)');
            $stmt->execute([$username, password_hash($password, PASSWORD_DEFAULT)]);
            $_SESSION['user_id'] = (int) db()->lastInsertId();
            $_SESSION['flash'] = 'Account created.';
            header('Location: ' . url('forum/'));
            exit;
        } catch (PDOException $exception) {
            if ((int) ($exception->errorInfo[1] ?? 0) === 1062) {
                $error = 'That username is already taken.';
            } else {
                $error = 'Could not create the account. Is the database installed?';
            }
        }
    }
}

$pageTitle = 'Sign up';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<h1>Create an account</h1>
<form class="panel" method="post" action="<?= e(url('forum/register.php')) ?>">
    <?= csrf_field() ?>
    <?php if ($error !== null): ?>
        <p class="error"><?= e($error) ?></p>
    <?php endif; ?>
    <label for="username">Username</label>
    <input id="username" name="username" required maxlength="24" autocomplete="username" value="<?= e((string) ($_POST['username'] ?? '')) ?>">
    <label for="password">Password</label>
    <input id="password" name="password" type="password" required minlength="8" autocomplete="new-password">
    <label for="password_again">Password again</label>
    <input id="password_again" name="password_again" type="password" required minlength="8" autocomplete="new-password">
    <div class="actions"><button class="btn" type="submit">Create account</button></div>
    <p>Already have an account? <a href="<?= e(url('forum/login.php')) ?>">Log in</a></p>
</form>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
