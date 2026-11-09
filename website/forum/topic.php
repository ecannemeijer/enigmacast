<?php

declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';

$id = (int) ($_GET['id'] ?? 0);
$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_login();
    csrf_check();
    $body = trim((string) ($_POST['body'] ?? ''));
    if ($body === '' || strlen($body) > 8000) {
        $error = 'Write a reply of at most 8000 characters.';
    } else {
        $user = current_user();
        $stmt = db()->prepare('INSERT INTO replies (topic_id, user_id, body) VALUES (?, ?, ?)');
        $stmt->execute([$id, (int) $user['id'], $body]);
        $_SESSION['flash'] = 'Reply posted.';
        header('Location: ' . url('forum/topic.php?id=' . $id));
        exit;
    }
}

$topic = null;
$replies = [];
try {
    $stmt = db()->prepare(
        'SELECT t.id, t.title, t.body, t.created_at, c.name AS category_name, u.username
         FROM topics t
         JOIN categories c ON c.id = t.category_id
         JOIN users u ON u.id = t.user_id
         WHERE t.id = ?'
    );
    $stmt->execute([$id]);
    $row = $stmt->fetch();
    $topic = $row === false ? null : $row;
    if ($topic !== null) {
        $replyStmt = db()->prepare(
            'SELECT r.body, r.created_at, u.username
             FROM replies r
             JOIN users u ON u.id = r.user_id
             WHERE r.topic_id = ?
             ORDER BY r.created_at ASC'
        );
        $replyStmt->execute([$id]);
        $replies = $replyStmt->fetchAll();
    }
} catch (PDOException) {
    $topic = null;
}

$pageTitle = $topic['title'] ?? 'Topic';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<p><a href="<?= e(url('forum/')) ?>">Back to the forum</a></p>
<?php if ($topic === null): ?>
    <h1>Topic not found</h1>
<?php else: ?>
    <h1><?= e($topic['title']) ?></h1>
    <article class="panel">
        <p class="meta"><span class="badge"><?= e($topic['category_name']) ?></span> <?= e($topic['username']) ?> · <?= e($topic['created_at']) ?></p>
        <p><?= nl2br(e($topic['body'])) ?></p>
    </article>
    <?php foreach ($replies as $reply): ?>
        <article class="panel reply">
            <p class="meta"><?= e($reply['username']) ?> · <?= e($reply['created_at']) ?></p>
            <p><?= nl2br(e($reply['body'])) ?></p>
        </article>
    <?php endforeach; ?>
    <section class="panel">
        <h2>Reply</h2>
        <?php if ($error !== null): ?>
            <p class="error"><?= e($error) ?></p>
        <?php endif; ?>
        <?php if (current_user() === null): ?>
            <p><a href="<?= e(url('forum/login.php?next=' . rawurlencode(url('forum/topic.php?id=' . $id)))) ?>">Log in</a> to reply.</p>
        <?php else: ?>
            <form method="post" action="<?= e(url('forum/topic.php?id=' . $id)) ?>">
                <?= csrf_field() ?>
                <label for="body">Your reply</label>
                <textarea id="body" name="body" required maxlength="8000"></textarea>
                <div class="actions"><button class="btn" type="submit">Post</button></div>
            </form>
        <?php endif; ?>
    </section>
<?php endif; ?>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
