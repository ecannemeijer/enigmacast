<?php

declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';

$categories = [];
$topics = [];
$dbError = null;

try {
    $categories = db()->query('SELECT id, slug, name FROM categories ORDER BY id')->fetchAll();
    $filter = $_GET['cat'] ?? '';
    if (!is_string($filter)) {
        $filter = '';
    }
    $sql = 'SELECT t.id, t.title, t.created_at, c.name AS category_name, c.slug, u.username,
                   (SELECT COUNT(*) FROM replies r WHERE r.topic_id = t.id) AS reply_count
            FROM topics t
            JOIN categories c ON c.id = t.category_id
            JOIN users u ON u.id = t.user_id';
    $params = [];
    if ($filter !== '') {
        $sql .= ' WHERE c.slug = ?';
        $params[] = $filter;
    }
    $sql .= ' ORDER BY t.created_at DESC LIMIT 100';
    $stmt = db()->prepare($sql);
    $stmt->execute($params);
    $topics = $stmt->fetchAll();
} catch (PDOException $exception) {
    $dbError = $exception->getMessage();
}

$pageTitle = 'Forum';
$active = 'forum';
require dirname(__DIR__) . '/includes/header.php';
?>
<h1>Forum</h1>
<p class="lead">Vragen over de installatie horen bij Hulp. Een wens voor de app hoort bij Feature request.</p>

<?php if ($dbError !== null): ?>
    <section class="panel">
        <p class="error">De database is nog niet klaar.</p>
        <p><a class="btn" href="<?= e(url('install.php')) ?>">Installeren</a></p>
    </section>
<?php else: ?>
    <div class="actions">
        <?php foreach ($categories as $category): ?>
            <a class="btn" href="<?= e(url('forum/?cat=' . rawurlencode($category['slug']))) ?>"><?= e($category['name']) ?></a>
        <?php endforeach; ?>
        <a class="btn" href="<?= e(url('forum/')) ?>">Alles</a>
        <a class="btn" href="<?= e(url('forum/new.php')) ?>">Nieuw bericht</a>
    </div>
    <section class="panel">
        <?php if ($topics === []): ?>
            <p>Nog geen berichten. Maak een account en plaats de eerste vraag of een feature request.</p>
        <?php else: ?>
            <ul class="topic-list">
                <?php foreach ($topics as $topic): ?>
                    <li>
                        <span class="badge"><?= e($topic['category_name']) ?></span>
                        <a href="<?= e(url('forum/topic.php?id=' . (int) $topic['id'])) ?>"><?= e($topic['title']) ?></a>
                        <p class="meta"><?= e($topic['username']) ?> · <?= e($topic['created_at']) ?> · <?= (int) $topic['reply_count'] ?> antwoorden</p>
                    </li>
                <?php endforeach; ?>
            </ul>
        <?php endif; ?>
    </section>
<?php endif; ?>
<?php require dirname(__DIR__) . '/includes/footer.php'; ?>
