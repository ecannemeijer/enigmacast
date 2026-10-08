<?php

declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';
require_login();

$categories = [];
$error = null;
try {
    $categories = db()->query('SELECT id, slug, name FROM categories ORDER BY id')->fetchAll();
} catch (PDOException) {
    $categories = [];
}

$picked = $_GET['cat'] ?? 'help';
if (!is_string($picked)) {
    $picked = 'help';
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $title = trim((string) ($_POST['title'] ?? ''));
    $body = trim((string) ($_POST['body'] ?? ''));
    $categoryId = (int) ($_POST['category_id'] ?? 0);
    $allowed = array_map(static fn (array $row): int => (int) $row['id'], $categories);
    if ($title === '' || strlen($title) > 120) {
        $error = 'Enter a title of at most 120 characters.';
    } elseif ($body === '' || strlen($body) > 8000) {
        $error = 'Write a post of at most 8000 characters.';
    } elseif (!in_array($categoryId, $allowed, true)) {
        $error = 'Choose Help or Feature request.';
    } else {
        $user = current_user();
        $stmt = db()->prepare('INSERT INTO topics (category_id, user_id, title, body) VALUES (?, ?, ?, ?)');
        $stmt->execute([$categoryId, (int) $user['id'], $title, $body]);
        $id = (int) db()->lastInsertId();
        $_SESSION['flash'] = 'Post created.';
        header('Location: ' . url('forum/topic.php?id=' . $id));
        exit;
    }
    $picked = '';
}

$pageTitle = 'New post';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<h1>New post</h1>
<?php if ($categories === []): ?>
    <section class="panel">
        <p class="error">The database is not ready yet.</p>
        <p><a class="btn" href="<?= e(url('install.php')) ?>">Install</a></p>
    </section>
<?php else: ?>
    <form class="panel" method="post" action="<?= e(url('forum/new.php')) ?>">
        <?= csrf_field() ?>
        <?php if ($error !== null): ?>
            <p class="error"><?= e($error) ?></p>
        <?php endif; ?>
        <label for="category_id">Board</label>
        <select id="category_id" name="category_id">
            <?php foreach ($categories as $category): ?>
                <option value="<?= (int) $category['id'] ?>" <?= ($picked === $category['slug'] || (string) ($_POST['category_id'] ?? '') === (string) $category['id']) ? 'selected' : '' ?>>
                    <?= e($category['name']) ?>
                </option>
            <?php endforeach; ?>
        </select>
        <label for="title">Title</label>
        <input id="title" name="title" required maxlength="120" value="<?= e((string) ($_POST['title'] ?? '')) ?>">
        <label for="body">Message</label>
        <textarea id="body" name="body" required maxlength="8000"><?= e((string) ($_POST['body'] ?? '')) ?></textarea>
        <div class="actions"><button class="btn" type="submit">Post</button></div>
    </form>
<?php endif; ?>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
