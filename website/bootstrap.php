<?php

declare(strict_types=1);

if (session_status() !== PHP_SESSION_ACTIVE) {
    session_start([
        'cookie_httponly' => true,
        'cookie_samesite' => 'Lax',
    ]);
}

function config(): array
{
    static $config = null;
    if ($config === null) {
        $config = require __DIR__ . '/config.php';
    }
    return $config;
}

function base_path(): string
{
    $script = str_replace('\\', '/', $_SERVER['SCRIPT_NAME'] ?? '');
    $dir = dirname($script);
    if (str_ends_with($dir, '/forum')) {
        $dir = dirname($dir);
    }
    if ($dir === '/' || $dir === '.' || $dir === '\\') {
        return '';
    }
    return rtrim($dir, '/');
}

function url(string $path = ''): string
{
    $base = base_path();
    $path = ltrim($path, '/');
    if ($path === '') {
        return $base === '' ? '/' : $base . '/';
    }
    return ($base === '' ? '' : $base) . '/' . $path;
}

function e(?string $value): string
{
    return htmlspecialchars($value ?? '', ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $cfg = config();
    $dsn = sprintf(
        'mysql:host=%s;dbname=%s;charset=utf8mb4',
        $cfg['db_host'],
        $cfg['db_name']
    );
    $pdo = new PDO($dsn, $cfg['db_user'], $cfg['db_pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    return $pdo;
}

function current_user(): ?array
{
    static $loaded = false;
    static $user = null;
    if ($loaded) {
        return $user;
    }
    $loaded = true;
    $id = $_SESSION['user_id'] ?? null;
    if (!is_int($id) && !(is_string($id) && ctype_digit($id))) {
        return null;
    }
    try {
        $stmt = db()->prepare('SELECT id, username FROM users WHERE id = ?');
        $stmt->execute([(int) $id]);
        $row = $stmt->fetch();
        $user = $row === false ? null : $row;
        if ($user === null) {
            unset($_SESSION['user_id']);
        }
    } catch (PDOException) {
        $user = null;
    }
    return $user;
}

function require_login(): void
{
    if (current_user() === null) {
        $_SESSION['flash'] = 'Log in to post a message.';
        $next = $_SERVER['REQUEST_URI'] ?? url('forum/');
        header('Location: ' . url('forum/login.php?next=' . rawurlencode($next)));
        exit;
    }
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf']) || !is_string($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['csrf'];
}

function csrf_field(): string
{
    return '<input type="hidden" name="csrf" value="' . e(csrf_token()) . '">';
}

function csrf_check(): void
{
    $sent = $_POST['csrf'] ?? '';
    $known = $_SESSION['csrf'] ?? '';
    if (!is_string($sent) || !is_string($known) || !hash_equals($known, $sent)) {
        http_response_code(400);
        exit('Invalid form. Go back and try again.');
    }
}

function flash(): ?string
{
    if (empty($_SESSION['flash']) || !is_string($_SESSION['flash'])) {
        return null;
    }
    $message = $_SESSION['flash'];
    unset($_SESSION['flash']);
    return $message;
}

function release_info(): array
{
    $release = [
        'versionCode' => null,
        'versionName' => 'unknown',
        'apkUrl' => 'https://update.velixatv.com/enigmacast.apk',
        'notes' => '',
    ];
    $path = dirname(__DIR__) . '/dist/enigmacast.json';
    if (!is_readable($path)) {
        return $release;
    }
    $decoded = json_decode((string) file_get_contents($path), true);
    if (!is_array($decoded)) {
        return $release;
    }
    return array_merge($release, $decoded);
}

function safe_next(string $next): string
{
    $next = trim($next);
    if ($next === '' || str_contains($next, '://') || str_starts_with($next, '//')) {
        return url('forum/');
    }
    $base = base_path();
    if ($base !== '' && !str_starts_with($next, $base . '/') && $next !== $base) {
        return url('forum/');
    }
    if ($base === '' && !str_starts_with($next, '/forum')) {
        return url('forum/');
    }
    return $next;
}
