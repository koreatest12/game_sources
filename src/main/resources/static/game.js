(() => {
  const canvas = document.getElementById('game');
  const ctx = canvas.getContext('2d');
  const scoreEl = document.getElementById('score');
  const livesEl = document.getElementById('lives');
  const bestEl = document.getElementById('best');
  const serverState = document.getElementById('server-state');
  const overlay = document.getElementById('overlay');
  const overlayTitle = document.getElementById('overlay-title');
  const overlayCopy = document.getElementById('overlay-copy');
  const startButton = document.getElementById('start');

  const W = canvas.width;
  const H = canvas.height;
  const keys = new Set();
  const assets = {};
  const assetPaths = {
    player: '/assets/player.svg',
    enemy: '/assets/enemy.svg',
    gem: '/assets/gem.svg',
    background: '/assets/background.svg'
  };

  const state = {
    running: false,
    paused: false,
    score: 0,
    lives: 3,
    best: Number(localStorage.getItem('game-sources-best') || 0),
    lastTime: 0,
    enemyTimer: 0,
    gemTimer: 0,
    player: { x: W / 2, y: H / 2, r: 24, speed: 280, invulnerable: 0 },
    enemies: [],
    gems: []
  };

  bestEl.textContent = state.best;

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = src;
    });
  }

  async function loadAssets() {
    for (const [name, src] of Object.entries(assetPaths)) {
      assets[name] = await loadImage(src);
    }
  }

  async function checkServer() {
    try {
      const response = await fetch('/health', { cache: 'no-store' });
      const json = await response.json();
      if (!response.ok || json.status !== 'UP') throw new Error('unhealthy');
      serverState.textContent = '서버 ONLINE';
      serverState.className = 'server-state ok';
    } catch (_) {
      serverState.textContent = '서버 OFFLINE';
      serverState.className = 'server-state bad';
    }
  }

  function reset() {
    state.score = 0;
    state.lives = 3;
    state.enemyTimer = 0;
    state.gemTimer = 0;
    state.enemies = [];
    state.gems = [];
    state.player.x = W / 2;
    state.player.y = H / 2;
    state.player.invulnerable = 0;
    scoreEl.textContent = '0';
    livesEl.textContent = '3';
  }

  function startGame() {
    reset();
    state.running = true;
    state.paused = false;
    state.lastTime = performance.now();
    overlay.classList.add('hidden');
    requestAnimationFrame(loop);
  }

  function endGame() {
    state.running = false;
    state.best = Math.max(state.best, state.score);
    localStorage.setItem('game-sources-best', String(state.best));
    bestEl.textContent = state.best;
    overlayTitle.textContent = 'GAME OVER';
    overlayCopy.textContent = `최종 점수 ${state.score}점 · R 또는 버튼으로 다시 시작`;
    startButton.textContent = '다시 시작';
    overlay.classList.remove('hidden');
  }

  function spawnEnemy() {
    const edge = Math.floor(Math.random() * 4);
    let x;
    let y;
    if (edge === 0) { x = -30; y = Math.random() * H; }
    if (edge === 1) { x = W + 30; y = Math.random() * H; }
    if (edge === 2) { x = Math.random() * W; y = -30; }
    if (edge === 3) { x = Math.random() * W; y = H + 30; }
    const speed = 95 + Math.min(180, state.score * 1.7) + Math.random() * 45;
    state.enemies.push({ x, y, r: 21, speed });
  }

  function spawnGem() {
    state.gems.push({
      x: 60 + Math.random() * (W - 120),
      y: 60 + Math.random() * (H - 120),
      r: 16,
      phase: Math.random() * Math.PI * 2
    });
  }

  function distance(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  function update(dt) {
    const p = state.player;
    let dx = 0;
    let dy = 0;
    if (keys.has('arrowleft') || keys.has('a')) dx -= 1;
    if (keys.has('arrowright') || keys.has('d')) dx += 1;
    if (keys.has('arrowup') || keys.has('w')) dy -= 1;
    if (keys.has('arrowdown') || keys.has('s')) dy += 1;
    if (dx || dy) {
      const len = Math.hypot(dx, dy);
      p.x += (dx / len) * p.speed * dt;
      p.y += (dy / len) * p.speed * dt;
    }
    p.x = Math.max(p.r, Math.min(W - p.r, p.x));
    p.y = Math.max(p.r, Math.min(H - p.r, p.y));
    p.invulnerable = Math.max(0, p.invulnerable - dt);

    state.enemyTimer -= dt;
    state.gemTimer -= dt;
    if (state.enemyTimer <= 0) {
      spawnEnemy();
      state.enemyTimer = Math.max(0.42, 1.35 - state.score * 0.008);
    }
    if (state.gemTimer <= 0 && state.gems.length < 4) {
      spawnGem();
      state.gemTimer = 1.0 + Math.random() * 0.8;
    }

    for (const enemy of state.enemies) {
      const vx = p.x - enemy.x;
      const vy = p.y - enemy.y;
      const len = Math.hypot(vx, vy) || 1;
      enemy.x += (vx / len) * enemy.speed * dt;
      enemy.y += (vy / len) * enemy.speed * dt;
    }

    state.gems = state.gems.filter(gem => {
      gem.phase += dt * 4;
      if (distance(p, gem) < p.r + gem.r) {
        state.score += 10;
        scoreEl.textContent = state.score;
        return false;
      }
      return true;
    });

    if (p.invulnerable <= 0) {
      for (const enemy of state.enemies) {
        if (distance(p, enemy) < p.r + enemy.r - 4) {
          state.lives -= 1;
          livesEl.textContent = state.lives;
          p.invulnerable = 1.25;
          enemy.x = -100;
          enemy.y = -100;
          if (state.lives <= 0) endGame();
          break;
        }
      }
    }

    state.enemies = state.enemies.filter(e => e.x > -150 && e.x < W + 150 && e.y > -150 && e.y < H + 150);
  }

  function drawSprite(img, x, y, size, alpha = 1, rotation = 0) {
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.translate(x, y);
    ctx.rotate(rotation);
    ctx.drawImage(img, -size / 2, -size / 2, size, size);
    ctx.restore();
  }

  function draw() {
    if (assets.background) ctx.drawImage(assets.background, 0, 0, W, H);
    else { ctx.fillStyle = '#0b1120'; ctx.fillRect(0, 0, W, H); }

    for (const gem of state.gems) {
      const scale = 36 + Math.sin(gem.phase) * 5;
      drawSprite(assets.gem, gem.x, gem.y, scale, 1, gem.phase * 0.18);
    }
    for (const enemy of state.enemies) drawSprite(assets.enemy, enemy.x, enemy.y, 48);

    const blink = state.player.invulnerable > 0 && Math.floor(state.player.invulnerable * 10) % 2 === 0;
    drawSprite(assets.player, state.player.x, state.player.y, 56, blink ? 0.35 : 1);

    if (state.paused) {
      ctx.fillStyle = 'rgba(3,7,18,.72)';
      ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = '#f8fafc';
      ctx.font = '800 44px system-ui';
      ctx.textAlign = 'center';
      ctx.fillText('PAUSED', W / 2, H / 2);
    }
  }

  function loop(now) {
    if (!state.running) return;
    const dt = Math.min(0.033, (now - state.lastTime) / 1000);
    state.lastTime = now;
    if (!state.paused) update(dt);
    draw();
    if (state.running) requestAnimationFrame(loop);
  }

  window.addEventListener('keydown', event => {
    const key = event.key.toLowerCase();
    if (['arrowleft', 'arrowright', 'arrowup', 'arrowdown', ' '].includes(key)) event.preventDefault();
    keys.add(key);
    if (key === ' ' && state.running) state.paused = !state.paused;
    if (key === 'r') startGame();
  });
  window.addEventListener('keyup', event => keys.delete(event.key.toLowerCase()));
  window.addEventListener('blur', () => { if (state.running) state.paused = true; });
  startButton.addEventListener('click', startGame);

  Promise.all([loadAssets(), checkServer()])
    .then(() => draw())
    .catch(error => {
      console.error(error);
      overlayCopy.textContent = '게임 리소스를 불러오지 못했습니다. 서버 로그를 확인하세요.';
    });
  setInterval(checkServer, 30000);
})();
