(() => {
  'use strict';

  const game = document.getElementById('game');
  const ctx = game.getContext('2d');
  const brainCanvas = document.getElementById('brain');
  const bctx = brainCanvas.getContext('2d');
  const $ = (id) => document.getElementById(id);

  const STORAGE_KEY = 'flyDoomAstraLearningV1';
  const ACTIONS = ['turnLeft', 'turnRight', 'advance', 'fire'];
  const keys = new Set();
  let paused = false;
  let autoplay = true;
  let speedMultiplier = 1;
  let lastTime = performance.now();
  let accumulator = 0;
  let generation = 1;
  let wave = 1;
  let kills = 0;
  let totalReward = 0;
  let learnCount = 0;
  let lastLoss = .5;
  let spawnTimer = 0;
  let waveTimer = 0;
  let supplyTimer = 0;
  let particles = [];
  let bullets = [];
  let enemies = [];
  let pickups = [];

  const rand = (a, b) => a + Math.random() * (b - a);
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const wrapAngle = (a) => Math.atan2(Math.sin(a), Math.cos(a));

  const player = {
    x: game.width / 2,
    y: game.height / 2,
    angle: -Math.PI / 2,
    radius: 11,
    hp: 100,
    maxHp: 100,
    damage: 1,
    speed: 145,
    fireRate: 4,
    cooldown: 0,
    flash: 0
  };

  const learning = loadLearning();
  const net = createNetwork(8, 12, 4, learning.network);
  const qTable = learning.qTable || {};
  learnCount = learning.learnCount || 0;

  function createNetwork(inputSize, hiddenSize, outputSize, saved) {
    const makeMatrix = (rows, cols) => Array.from({ length: rows }, () =>
      Array.from({ length: cols }, () => rand(-.7, .7)));
    return {
      w1: saved?.w1 || makeMatrix(hiddenSize, inputSize),
      b1: saved?.b1 || Array(hiddenSize).fill(0),
      w2: saved?.w2 || makeMatrix(outputSize, hiddenSize),
      b2: saved?.b2 || Array(outputSize).fill(0),
      hidden: Array(hiddenSize).fill(0),
      output: Array(outputSize).fill(0)
    };
  }

  function loadLearning() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}; }
    catch { return {}; }
  }

  function saveLearning() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      qTable,
      network: { w1: net.w1, b1: net.b1, w2: net.w2, b2: net.b2 },
      learnCount
    }));
  }

  function sigmoid(x) { return 1 / (1 + Math.exp(-x)); }
  function forward(inputs) {
    net.hidden = net.w1.map((row, i) => sigmoid(row.reduce((s, w, j) => s + w * inputs[j], net.b1[i])));
    net.output = net.w2.map((row, i) => sigmoid(row.reduce((s, w, j) => s + w * net.hidden[j], net.b2[i])));
    return net.output;
  }

  function learnNetwork(inputs, actionIndex, reward) {
    const target = clamp(.5 + reward * .06, 0, 1);
    const out = forward(inputs)[actionIndex];
    const error = target - out;
    const lr = .035;
    const outGrad = error * out * (1 - out);
    net.w2[actionIndex] = net.w2[actionIndex].map((w, j) => w + lr * outGrad * net.hidden[j]);
    net.b2[actionIndex] += lr * outGrad;
    for (let h = 0; h < net.hidden.length; h++) {
      const hidden = net.hidden[h];
      const hg = hidden * (1 - hidden) * net.w2[actionIndex][h] * outGrad;
      for (let i = 0; i < inputs.length; i++) net.w1[h][i] += lr * .35 * hg * inputs[i];
      net.b1[h] += lr * .35 * hg;
    }
    lastLoss = .92 * lastLoss + .08 * Math.abs(error);
    learnCount++;
  }

  function stateKey(inputs) {
    return inputs.map(v => Math.round(v * 2)).join('');
  }

  function chooseAction(inputs) {
    const outputs = forward(inputs);
    const key = stateKey(inputs);
    const q = qTable[key] || (qTable[key] = [0, 0, 0, 0]);
    const epsilon = Math.max(.04, .22 - learnCount / 8000);
    if (Math.random() < epsilon) return Math.floor(Math.random() * ACTIONS.length);
    let best = 0;
    let score = -Infinity;
    for (let i = 0; i < ACTIONS.length; i++) {
      const s = outputs[i] * .6 + sigmoid(q[i]) * .4;
      if (s > score) { score = s; best = i; }
    }
    return best;
  }

  function rewardAction(inputs, actionIndex, reward) {
    const key = stateKey(inputs);
    const q = qTable[key] || (qTable[key] = [0, 0, 0, 0]);
    q[actionIndex] += .16 * (reward - q[actionIndex]);
    totalReward += reward;
    learnNetwork(inputs, actionIndex, reward);
    if (learnCount % 12 === 0) saveLearning();
  }

  function sensors() {
    let left = 0, front = 0, right = 0;
    let nearest = null;
    let nearestD = Infinity;
    for (const e of enemies) {
      const d = distance(player, e);
      if (d < nearestD) { nearest = e; nearestD = d; }
      const a = wrapAngle(Math.atan2(e.y - player.y, e.x - player.x) - player.angle);
      const strength = clamp(1 - d / 420, 0, 1);
      if (a < -.35) left = Math.max(left, strength);
      else if (a > .35) right = Math.max(right, strength);
      else front = Math.max(front, strength);
    }
    let item = 0;
    for (const p of pickups) item = Math.max(item, clamp(1 - distance(player, p) / 300, 0, 1));
    const lowHp = 1 - player.hp / player.maxHp;
    const wall = Math.min(player.x, player.y, game.width - player.x, game.height - player.y) < 45 ? 1 : 0;
    const danger = nearest ? clamp(1 - nearestD / 180, 0, 1) : 0;
    const growth = clamp((player.damage - 1) / 5 + (player.maxHp - 100) / 250, 0, 1);
    return [left, front, right, item, lowHp, danger, wall, growth];
  }

  function log(text) {
    const li = document.createElement('li');
    const sec = Math.floor((performance.now() / 1000) % 3600);
    li.textContent = `${String(Math.floor(sec / 60)).padStart(2,'0')}:${String(sec % 60).padStart(2,'0')} ${text}`;
    $('combatLog').prepend(li);
    while ($('combatLog').children.length > 16) $('combatLog').lastChild.remove();
  }

  function spawnEnemy() {
    const side = Math.floor(Math.random() * 4);
    let x, y;
    if (side === 0) { x = rand(0, game.width); y = -20; }
    if (side === 1) { x = game.width + 20; y = rand(0, game.height); }
    if (side === 2) { x = rand(0, game.width); y = game.height + 20; }
    if (side === 3) { x = -20; y = rand(0, game.height); }
    const elite = Math.random() < Math.min(.26, wave * .018);
    const hp = (elite ? 5 : 2) + Math.floor(wave / 4);
    enemies.push({
      x, y,
      radius: elite ? 16 : 10,
      hp,
      maxHp: hp,
      speed: (elite ? 54 : 72) + wave * 2.3,
      damage: elite ? 16 : 9,
      elite,
      wobble: rand(0, Math.PI * 2)
    });
  }

  function spawnPickup(x = rand(80, game.width - 80), y = rand(80, game.height - 80), forcedType) {
    const types = ['damage', 'health', 'speed', 'fire', 'heal'];
    pickups.push({ x, y, radius: 10, type: forcedType || types[Math.floor(Math.random() * types.length)], life: 18 });
  }

  function fire() {
    if (player.cooldown > 0) return false;
    player.cooldown = 1 / player.fireRate;
    const vx = Math.cos(player.angle), vy = Math.sin(player.angle);
    bullets.push({ x: player.x + vx * 15, y: player.y + vy * 15, vx: vx * 470, vy: vy * 470, life: 1.3, damage: player.damage });
    return true;
  }

  function applyPickup(p) {
    if (p.type === 'damage') { player.damage += .25; log('⚡ 플라스마 코어 · 공격력 증가'); }
    if (p.type === 'health') { player.maxHp += 12; player.hp = Math.min(player.maxHp, player.hp + 12); log('💚 생체막 · 최대 체력 증가'); }
    if (p.type === 'speed') { player.speed += 10; log('🪽 날개 강화 · 이동 속도 증가'); }
    if (p.type === 'fire') { player.fireRate += .35; log('💠 신경 점화 · 발사 속도 증가'); }
    if (p.type === 'heal') { player.hp = Math.min(player.maxHp, player.hp + 35); log('🍎 회복 영양체 · 체력 회복'); }
    burst(p.x, p.y, '#ffe75d', 16);
  }

  function burst(x, y, color, n = 10) {
    for (let i = 0; i < n; i++) particles.push({ x, y, vx: rand(-90,90), vy: rand(-90,90), life: rand(.2,.7), color });
  }

  function resetRun() {
    generation++;
    wave = 1; kills = 0; totalReward = 0;
    enemies = []; bullets = []; pickups = []; particles = [];
    Object.assign(player, { x: game.width/2, y: game.height/2, angle: -Math.PI/2, hp: 100, maxHp: 100, damage: 1, speed: 145, fireRate: 4, cooldown: 0 });
    spawnPickup(player.x + 70, player.y, 'damage');
    log(`세대 ${String(generation).padStart(3,'0')} · 아레나 진입`);
  }

  function update(dt) {
    player.cooldown = Math.max(0, player.cooldown - dt);
    player.flash = Math.max(0, player.flash - dt);
    spawnTimer -= dt;
    waveTimer += dt;
    supplyTimer += dt;

    if (spawnTimer <= 0) {
      spawnEnemy();
      spawnTimer = Math.max(.18, 1.05 - wave * .035);
    }
    if (waveTimer > 20) {
      wave++;
      waveTimer = 0;
      log(`WAVE ${String(wave).padStart(2,'0')} 진입`);
    }
    if (supplyTimer > 7) {
      supplyTimer = 0;
      spawnPickup();
    }

    const input = sensors();
    let action = -1;
    if (autoplay) {
      action = chooseAction(input);
      const a = ACTIONS[action];
      if (a === 'turnLeft') player.angle -= 2.35 * dt;
      if (a === 'turnRight') player.angle += 2.35 * dt;
      if (a === 'advance') {
        player.x += Math.cos(player.angle) * player.speed * dt;
        player.y += Math.sin(player.angle) * player.speed * dt;
      }
      if (a === 'fire') fire();
      rewardAction(input, action, .012);
    } else {
      if (keys.has('ArrowLeft')) player.angle -= 2.6 * dt;
      if (keys.has('ArrowRight')) player.angle += 2.6 * dt;
      let dx = 0, dy = 0;
      if (keys.has('KeyW')) { dx += Math.cos(player.angle); dy += Math.sin(player.angle); }
      if (keys.has('KeyS')) { dx -= Math.cos(player.angle); dy -= Math.sin(player.angle); }
      if (keys.has('KeyA')) { dx += Math.cos(player.angle - Math.PI/2); dy += Math.sin(player.angle - Math.PI/2); }
      if (keys.has('KeyD')) { dx += Math.cos(player.angle + Math.PI/2); dy += Math.sin(player.angle + Math.PI/2); }
      const len = Math.hypot(dx,dy) || 1;
      player.x += dx / len * player.speed * dt;
      player.y += dy / len * player.speed * dt;
      if (keys.has('Space')) fire();
    }

    const beforeX = player.x, beforeY = player.y;
    player.x = clamp(player.x, player.radius, game.width - player.radius);
    player.y = clamp(player.y, player.radius, game.height - player.radius);
    if (autoplay && action >= 0 && (beforeX !== player.x || beforeY !== player.y)) rewardAction(input, action, -.18);

    for (const b of bullets) { b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt; }
    bullets = bullets.filter(b => b.life > 0 && b.x > -20 && b.y > -20 && b.x < game.width+20 && b.y < game.height+20);

    for (const e of enemies) {
      e.wobble += dt * 4;
      const a = Math.atan2(player.y - e.y, player.x - e.x) + Math.sin(e.wobble) * .16;
      e.x += Math.cos(a) * e.speed * dt;
      e.y += Math.sin(a) * e.speed * dt;
    }

    for (const b of bullets) for (const e of enemies) {
      if (b.life > 0 && e.hp > 0 && distance(b, e) < e.radius + 3) {
        b.life = 0; e.hp -= b.damage;
        burst(b.x, b.y, '#85ffa5', 5);
        if (autoplay && action >= 0) rewardAction(input, action, .14);
        if (e.hp <= 0) {
          kills++;
          burst(e.x, e.y, e.elite ? '#ffcf5a' : '#74ff98', 18);
          if (Math.random() < .8) spawnPickup(e.x, e.y);
          if (autoplay && action >= 0) rewardAction(input, action, 1.15 + (e.elite ? .6 : 0));
        }
      }
    }
    enemies = enemies.filter(e => e.hp > 0);

    for (const e of enemies) {
      if (distance(player, e) < player.radius + e.radius) {
        player.hp -= e.damage * dt * 1.7;
        player.flash = .08;
        if (autoplay && action >= 0) rewardAction(input, action, -.08);
      }
    }

    for (const p of pickups) {
      p.life -= dt;
      if (distance(player, p) < player.radius + p.radius + 3) { p.life = 0; applyPickup(p); if (autoplay && action >= 0) rewardAction(input, action, .45); }
    }
    pickups = pickups.filter(p => p.life > 0);

    for (const p of particles) { p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= .96; p.vy *= .96; p.life -= dt; }
    particles = particles.filter(p => p.life > 0);

    if (player.hp <= 0) {
      log(`☠ 세대 ${String(generation).padStart(3,'0')} 종료 · ${kills} 처치`);
      saveLearning();
      resetRun();
    }
    updateUI();
  }

  function draw() {
    ctx.clearRect(0,0,game.width,game.height);
    const g = ctx.createRadialGradient(player.x, player.y, 10, player.x, player.y, 420);
    g.addColorStop(0, '#10251f'); g.addColorStop(1, '#040706');
    ctx.fillStyle = g; ctx.fillRect(0,0,game.width,game.height);

    ctx.strokeStyle = '#11241f'; ctx.lineWidth = 1;
    for (let x = 0; x < game.width; x += 48) { ctx.beginPath(); ctx.moveTo(x,0); ctx.lineTo(x,game.height); ctx.stroke(); }
    for (let y = 0; y < game.height; y += 48) { ctx.beginPath(); ctx.moveTo(0,y); ctx.lineTo(game.width,y); ctx.stroke(); }

    for (const p of pickups) {
      const colors = { damage:'#ffcf5a', health:'#67ffa7', speed:'#8fd6ff', fire:'#d999ff', heal:'#ff7389' };
      ctx.save(); ctx.translate(p.x,p.y); ctx.rotate(performance.now()/700); ctx.fillStyle = colors[p.type];
      ctx.fillRect(-7,-7,14,14); ctx.restore();
    }

    for (const b of bullets) { ctx.fillStyle = '#eaff73'; ctx.beginPath(); ctx.arc(b.x,b.y,3,0,Math.PI*2); ctx.fill(); }
    for (const e of enemies) {
      ctx.save(); ctx.translate(e.x,e.y); ctx.fillStyle = e.elite ? '#ff8b59' : '#b8ff70';
      ctx.beginPath(); ctx.ellipse(0,0,e.radius*.7,e.radius,0,0,Math.PI*2); ctx.fill();
      ctx.strokeStyle = '#203d2e'; ctx.beginPath(); ctx.moveTo(-e.radius*.4,-2); ctx.lineTo(-e.radius*1.1,-8); ctx.moveTo(e.radius*.4,-2); ctx.lineTo(e.radius*1.1,-8); ctx.stroke();
      ctx.restore();
      if (e.elite) { ctx.fillStyle = '#30140c'; ctx.fillRect(e.x-16,e.y-e.radius-10,32,3); ctx.fillStyle='#ff8b59'; ctx.fillRect(e.x-16,e.y-e.radius-10,32*(e.hp/e.maxHp),3); }
    }
    for (const p of particles) { ctx.globalAlpha = clamp(p.life*2,0,1); ctx.fillStyle=p.color; ctx.fillRect(p.x,p.y,3,3); }
    ctx.globalAlpha = 1;

    ctx.save(); ctx.translate(player.x,player.y); ctx.rotate(player.angle);
    ctx.fillStyle = player.flash > 0 ? '#ff6677' : '#78fff0';
    ctx.beginPath(); ctx.moveTo(15,0); ctx.lineTo(-9,-9); ctx.lineTo(-5,0); ctx.lineTo(-9,9); ctx.closePath(); ctx.fill();
    ctx.strokeStyle='#eaffff'; ctx.beginPath(); ctx.moveTo(0,0); ctx.lineTo(24,0); ctx.stroke(); ctx.restore();

    drawBrain();
  }

  function drawBrain() {
    const W = brainCanvas.width, H = brainCanvas.height;
    bctx.clearRect(0,0,W,H); bctx.fillStyle='#050807'; bctx.fillRect(0,0,W,H);
    const inputs = sensors(); const outs = forward(inputs);
    const layers = [
      inputs.map((v,i)=>({x:55,y:25+i*28,v,label:['L','F','R','ITEM','HP','DANGER','WALL','GROW'][i]})),
      net.hidden.map((v,i)=>({x:210,y:16+i*19,v,label:`H${i+1}`})),
      outs.map((v,i)=>({x:365,y:55+i*45,v,label:ACTIONS[i]}))
    ];
    for (let i=0;i<layers[0].length;i++) for (let h=0;h<layers[1].length;h++) lineNode(layers[0][i], layers[1][h], net.w1[h][i]);
    for (let h=0;h<layers[1].length;h++) for (let o=0;o<layers[2].length;o++) lineNode(layers[1][h], layers[2][o], net.w2[o][h]);
    for (const layer of layers) for (const n of layer) {
      const r=4+n.v*5; bctx.fillStyle=`rgba(116,255,152,${.2+n.v*.8})`; bctx.beginPath(); bctx.arc(n.x,n.y,r,0,Math.PI*2); bctx.fill();
    }
  }
  function lineNode(a,b,w) {
    const alpha = clamp(Math.abs(w)*.16, .025, .24); bctx.strokeStyle = w >= 0 ? `rgba(116,255,152,${alpha})` : `rgba(120,170,255,${alpha})`;
    bctx.lineWidth=.5+Math.min(1.3,Math.abs(w)); bctx.beginPath(); bctx.moveTo(a.x,a.y); bctx.lineTo(b.x,b.y); bctx.stroke();
  }

  function updateUI() {
    $('wave').textContent = String(wave).padStart(2,'0');
    $('hp').textContent = `${Math.max(0,Math.ceil(player.hp))} / ${player.maxHp}`;
    $('kills').textContent = kills;
    $('generation').textContent = String(generation).padStart(3,'0');
    $('aiLevel').textContent = 1 + Math.floor(learnCount / 100);
    $('reward').textContent = totalReward.toFixed(1);
    $('learnCount').textContent = learnCount;
    $('loss').textContent = lastLoss.toFixed(3);
    $('damageStat').textContent = `${player.damage.toFixed(2)}×`;
    $('healthStat').textContent = player.maxHp;
    $('speedStat').textContent = `${(player.speed/145).toFixed(2)}×`;
    $('fireStat').textContent = `${player.fireRate.toFixed(1)}/초`;
  }

  function frame(now) {
    const realDt = Math.min(.05, (now - lastTime) / 1000); lastTime = now;
    if (!paused) {
      accumulator += realDt * speedMultiplier;
      const step = 1/60;
      let guard = 0;
      while (accumulator >= step && guard++ < 10) { update(step); accumulator -= step; }
    }
    draw();
    requestAnimationFrame(frame);
  }

  document.addEventListener('keydown', (e) => {
    if (['Space','ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.code)) e.preventDefault();
    keys.add(e.code);
    if (e.code === 'KeyP') togglePause();
  });
  document.addEventListener('keyup', e => keys.delete(e.code));

  function togglePause() { paused=!paused; $('pauseBtn').textContent = paused ? '▶ 계속' : 'Ⅱ 일시정지'; }
  $('pauseBtn').addEventListener('click', togglePause);
  $('resetBtn').addEventListener('click', resetRun);
  $('modeBtn').addEventListener('click', () => { autoplay=!autoplay; $('modeBtn').textContent = autoplay ? '🤖 자동 플레이' : '🎮 직접 조작'; });
  $('soundBtn').addEventListener('click', () => { $('soundBtn').textContent='🔇 소리 꺼짐'; });
  document.querySelectorAll('.speed').forEach(btn => btn.addEventListener('click', () => {
    speedMultiplier=Number(btn.dataset.speed); document.querySelectorAll('.speed').forEach(b=>b.classList.toggle('active',b===btn));
  }));

  spawnPickup(player.x + 70, player.y, 'damage');
  log('세대 001 · 아레나 진입');
  updateUI();
  requestAnimationFrame(frame);
})();
