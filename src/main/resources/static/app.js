'use strict';
const $ = (id) => document.getElementById(id);
const state = { runs: [], calibs: [], runId: null, data: null, q: {} };

const CH_COLORS = ['#5db3ff','#4fd08b','#ffb454','#c792ea','#f78c6c','#82aaff'];
const CH_NAMES = ['c1 Fx','c2 Fy','c3 Fz','c4 Mx','c5 My','c6 Mz'];

async function api(path, opts) {
  const res = await fetch(path, opts);
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = text; }
  if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
  return body;
}
function banner(msg, ok) {
  const b = $('banner');
  b.textContent = msg;
  b.className = 'banner ' + (ok ? 'ok' : 'err');
  setTimeout(() => b.classList.add('hidden'), 4000);
}
function fmt(v, d = 4) {
  if (v === null || v === undefined) return '—';
  if (typeof v !== 'number') return String(v);
  if (!Number.isFinite(v)) return '×';
  if (Math.abs(v) >= 1e5 || (Math.abs(v) > 0 && Math.abs(v) < 1e-4)) return v.toExponential(3);
  return Number(v.toFixed(d)).toString();
}
function vecValues(node) { return node.values; }

async function loadState() {
  const s = await api('/api/state');
  state.runs = s.runs;
  state.calibs = s.calibVersions;
  $('runSelect').innerHTML = s.runs.map(r => `<option value="${r.runId}">${r.runId}. ${r.name}</option>`).join('');
  $('calibVersion').innerHTML = s.calibVersions.map(c => `<option value="${c.code}">${c.code} — ${c.label}</option>`).join('');
  state.runId = s.runs[0]?.runId ?? null;
  $('runSelect').value = state.runId;
  syncControlsFromRun();
}

function syncControlsFromRun() {
  const r = state.runs.find(x => x.runId === Number(state.runId));
  if (!r) return;
  $('driftMode').value = r.driftMode;
  $('rotationOrder').value = r.rotationOrder;
  $('calibVersion').value = r.calibVersion;
}

async function refresh() {
  const params = new URLSearchParams({
    driftMode: $('driftMode').value,
    rotationOrder: $('rotationOrder').value,
    calibVersion: $('calibVersion').value,
  });
  state.data = await api(`/api/runs/${state.runId}/analysis?` + params);
  render();
}

// ---------- SVG 基础 ----------
function svgEl(tag, attrs, text) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
  if (text !== undefined) el.textContent = text;
  return el;
}
function clearSvg(svg) { while (svg.firstChild) svg.removeChild(svg.firstChild); }
function scale(lo, hi, a, b) {
  if (hi === lo) return (a + b) / 2;
  return (v) => a + (v - lo) / (hi - lo) * (b - a);
}
function line(svg, x1, y1, x2, y2, attrs) {
  svg.appendChild(svgEl('line', Object.assign({x1, y1, x2, y2, stroke: '#28324a', 'stroke-width': 1}, attrs || {})));
}
function text(svg, x, y, s, attrs) {
  svg.appendChild(svgEl('text', Object.assign({x, y, fill: '#93a0b8', 'font-size': 11}, attrs || {}), s));
}

// ---------- ① 状态序列 + 漂移曲线 ----------
function renderSequence() {
  const d = state.data;
  const svg = $('seqChart'); clearSvg(svg);
  const W = 960, H = 240, P = {l: 52, r: 14, t: 16, b: 30};
  const allT = [];
  d.tareRows.forEach(t => allT.push(t.t));
  d.testRows.forEach(t => allT.push(t.t));
  const tLo = Math.min(...allT) - 5, tHi = Math.max(...allT) + 5;
  // 以 c3（主法向力通道）漂移为代表画全线，六通道折线叠加（归一化）
  const allVals = [];
  d.tareRows.forEach(r => r.channels.forEach(v => allVals.push(v)));
  if (allVals.length === 0) allVals.push(0, 1);
  const vLo = Math.min(...allVals) - .05, vHi = Math.max(...allVals) + .05;
  const x = scale(tLo, tHi, P.l, W - P.r);
  const y = scale(vLo, vHi, H - P.b, P.t);

  line(svg, P.l, P.t, P.l, H - P.b);
  line(svg, P.l, H - P.b, W - P.r, H - P.b);
  for (let g = 0; g <= 4; g++) {
    const gv = vLo + (vHi - vLo) * g / 4;
    const gy = y(gv);
    line(svg, P.l, gy, W - P.r, gy, {'stroke-dasharray': '2 3'});
    text(svg, 6, gy + 3, fmt(gv, 3));
  }
  text(svg, W - P.r, H - 8, 't (s)', {'text-anchor': 'end'});

  // 六通道漂移折线（用每个锨点的 fitAtT 或原值）
  for (let c = 0; c < 6; c++) {
    const pts = d.tareRows.filter(r => !r.excluded).map(r => {
      const v = r.fitAtT ? r.fitAtT[c] : r.channels[c];
      return [x(r.t), y(v)];
    });
    if (d.driftMode === 'LINEAR' && pts.length >= 2) {
      const ext = d.testRows.some(t => t.extrapolated);
      // 外推延伸到外测点
      const tmin = d.tareEnvelope.tMin, tmax = d.tareEnvelope.tMax;
      if (ext) {
        const row = d.tareRows.find(r => r.t === tmin);
        const row2 = d.tareRows.find(r => r.t === tmax);
        const v0 = row.fitAtT ? row.fitAtT[c] : row.channels[c];
        const v1 = row2.fitAtT ? row2.fitAtT[c] : row2.channels[c];
        const sl = (v1 - v0) / (tmax - tmin);
        const tE = Math.max(...d.testRows.filter(t => t.extrapolated).map(t => t.t));
        svg.appendChild(svgEl('line', {
          x1: x(tmax), y1: y(v1), x2: x(tE), y2: y(v1 + sl * (tE - tmax)),
          stroke: CH_COLORS[c], 'stroke-width': 1.2, 'stroke-dasharray': '5 4', opacity: .8,
        }));
      }
    }
    if (pts.length) {
      svg.appendChild(svgEl('polyline', {
        points: pts.map(p => p.join(',')).join(' '),
        fill: 'none', stroke: CH_COLORS[c], 'stroke-width': 1.6, opacity: .85,
      }));
    }
  }
  // 锨点（方框，拒绝灰显）与测点（圆）
  d.tareRows.forEach(r => {
    for (let c = 0; c < 6; c++) {
      svg.appendChild(svgEl('rect', {
        x: x(r.t) - 3.5, y: y(r.channels[c]) - 3.5, width: 7, height: 7,
        fill: r.excluded ? '#55607a' : CH_COLORS[c], opacity: r.excluded ? .5 : .95,
        stroke: '#0f1420', 'stroke-width': .5,
      }));
    }
  });
  d.testRows.forEach(r => {
    const xs = x(r.t);
    line(svg, xs, P.t, xs, H - P.b, {stroke: r.extrapolated ? '#ffb454' : '#3a4663', 'stroke-dasharray': '3 3'});
    svg.appendChild(svgEl('circle', {cx: xs, cy: H - P.b + 10, r: 4.5, fill: r.qPositive ? '#5db3ff' : '#ff6b6b'}));
    text(svg, xs - 10, P.t + 10, r.t + 's', {fill: r.extrapolated ? '#ffb454' : '#93a0b8'});
  });
  // 包络阴影
  if (d.tareEnvelope.valid) {
    svg.appendChild(svgEl('rect', {
      x: x(d.tareEnvelope.tMin), y: P.t, width: x(d.tareEnvelope.tMax) - x(d.tareEnvelope.tMin),
      height: H - P.b - P.t, fill: '#5db3ff', opacity: .05,
    }));
  }
}

function renderTareControls() {
  const d = state.data;
  const el = $('tareControls');
  el.innerHTML = '';
  d.tareRows.forEach(r => {
    const chip = document.createElement('label');
    chip.className = 'tare-chip' + (r.excluded ? ' excluded' : '');
    chip.innerHTML = `<input type="checkbox" ${r.excluded ? '' : 'checked'}>
      空载锨点 t=${r.t}s ${r.excluded ? '（已拒绝）' : ''}`;
    chip.querySelector('input').addEventListener('change', async (e) => {
      try {
        await api(`/api/samples/${r.sampleId}/tare-excluded`, {
          method: 'PUT', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({excluded: !e.target.checked}),
        });
        await refresh();
      } catch (err) { banner(err.message, false); }
    });
    el.appendChild(chip);
  });
  const env = d.tareEnvelope;
  $('envelopeNote').textContent = env.valid
    ? `本 run 空载包络：[${env.tMin}, ${env.tMax}] s；包络外测点橙色标注，仅使用本 run 锨点，绝不借用相邻 run。`
    : '可用空载锨点不足 2 个，无法拟合漂移。';
  $('seqLegend').innerHTML =
    '<span><i style="background:#5db3ff"></i>漂移/锨点（六通道）</span>' +
    '<span><i style="background:#5db3ff;border-radius:50%"></i>测点 q&gt;0</span>' +
    '<span><i style="background:#ff6b6b;border-radius:50%"></i>测点 q≤0</span>' +
    '<span><i style="background:#ffb454"></i>外推区间/测点</span>';
}

// ---------- ② 六通道 raw / drift / zeroed 条形对比 ----------
function renderChannels() {
  const d = state.data;
  const svg = $('channelChart'); clearSvg(svg);
  const W = 960, H = 300, P = {l: 44, r: 12, t: 14, b: 40};
  const rows = d.testRows;
  const groups = rows.length;
  const gw = (W - P.l - P.r) / groups;
  const vals = [];
  rows.forEach(r => {
    vals.push(...r.chain.raw.values, ...r.chain.zeroed.values);
  });
  const lo = Math.min(...vals, 0), hi = Math.max(...vals, 1);
  const y = scale(lo, hi, H - P.b, P.t);
  line(svg, P.l, y(0), W - P.r, y(0), {stroke: '#93a0b8', 'stroke-width': 1});
  rows.forEach((r, gi) => {
    const gx = P.l + gi * gw;
    const raw = r.chain.raw.values, z = r.chain.zeroed.values;
    const dr = r.chain.drift.values;
    const bw = gw / 14;
    for (let c = 0; c < 6; c++) {
      const bx = gx + 4 + c * 2 * bw;
      // raw 浅色
      bar(svg, bx, y(raw[c]), bw * .8, y(0), CH_COLORS[c], .35);
      // zeroed 实色
      bar(svg, bx + bw, y(z[c]), bw * .8, y(0), CH_COLORS[c], .95);
    }
    text(svg, gx + gw / 2, H - 22, `t=${r.t}s`, {'text-anchor': 'middle', fill: '#e8edf7'});
    text(svg, gx + gw / 2, H - 8,
      `α=${r.alphaDeg}° β=${r.betaDeg}° q=${r.qPa}`, {'text-anchor': 'middle'});
    if (r.extrapolated) {
      svg.appendChild(svgEl('rect', {x: gx + 2, y: P.t, width: gw - 4, height: H - P.b - P.t,
        fill: 'none', stroke: '#ffb454', 'stroke-dasharray': '4 3'}));
    }
  });
  text(svg, 8, P.t + 8, fmt(hi, 1));
  text(svg, 8, H - P.b, fmt(lo, 1));
  $('channelLegend').innerHTML =
    CH_NAMES.map((n, i) => `<span><i style="background:${CH_COLORS[i]}"></i>${n}</span>`).join('')
    + '<span style="opacity:.7">浅色=原值，实色=零线修正后</span>';
}
function bar(svg, x, yv, w, y0, color, op) {
  svg.appendChild(svgEl('rect', {
    x, y: Math.min(yv, y0), width: w, height: Math.max(1, Math.abs(yv - y0)),
    fill: color, opacity: op,
  }));
}

// ---------- ③ 轴系示意 ----------
function renderAxes() {
  const d = state.data;
  const svg = $('axesSvg'); clearSvg(svg);
  const O = [150, 140];
  axis3(svg, O, [95, 30], '#5db3ff', 'x 机头');
  axis3(svg, O, [-70, 20], '#4fd08b', 'y 左翼');
  axis3(svg, O, [20, -85], '#ffb454', 'z 上');
  // 角度正方向圆弧
  svg.appendChild(svgEl('path', {d: arc(O, 36, -80, -20), fill: 'none',
    stroke: '#f78c6c', 'stroke-width': 1.4, 'marker-end': 'url(#arr)'}));
  text(svg, O[0] + 44, O[1] - 34, '+α 抬头', {fill: '#f78c6c', 'font-size': 11});
  svg.appendChild(svgEl('path', {d: arc(O, 54, 8, 34), fill: 'none',
    stroke: '#c792ea', 'stroke-width': 1.4}));
  text(svg, O[0] - 86, O[1] - 6, '+β 向左', {fill: '#c792ea', 'font-size': 11});
  // defs arrow
  const defs = svgEl('defs', {});
  defs.innerHTML = '<marker id="arr" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">'
    + '<path d="M0,0 L6,3 L0,6 Z" fill="#f78c6c"/></marker>';
  svg.insertBefore(defs, svg.firstChild);
  const first = d.testRows[0];
  $('conventions').innerHTML = `
    <div><b>模型坐标（右手系）：</b>x 机头、y 左翼、z 向上。</div>
    <div><b>角度正方向：</b>α 抬头为正，绕 +y 右手定则；β 机头向 +y（左翼）为正，绕 +z。</div>
    <div><b>旋转次序（可执行）：</b>
      <code>R_w = Ry(−α)·Rz(β)</code>（YZ，默认）；交换后
      <code>R_w = Rz(β)·Ry(−α)</code>（ZY）。两矩阵均按主动旋转、角度取弧度。</div>
    <div><b>当前选择：</b>${d.rotationOrder}；当前行矩阵已在“系数回查”里给出。</div>
    <div><b>力矩平移：</b><code>M_ref = M_bal + rRef × F</code>，叉积分量
      Mx=ryFz−rzFy, My=rzFx−rxFz, Mz=rxFy−ryFx。</div>
    <div><b>系数定义：</b>Cd=−Fxw/qS，Cy=Fyw/qS，CL=−Fzw/qS，
      Croll=Mxw/qSb，Cm=Myw/qSc̄，Cn=Mzw/qSb。<b>q≤0 不生成系数</b>。</div>
    <div><b>标定版本：</b>${d.calibVersion} — K 6×6、安装矩阵、rRef、S/b/c̄ 均随结果链输出。</div>`;
}
function axis3(svg, o, p, color, label) {
  line(svg, o[0], o[1], o[0] + p[0], o[1] + p[1], {stroke: color, 'stroke-width': 2});
  svg.appendChild(svgEl('polygon', {
    points: arrowHead(o, p), fill: color,
  }));
  text(svg, o[0] + p[0] + 4, o[1] + p[1] + 4, label, {fill: color, 'font-size': 12});
}
function arrowHead(o, p) {
  const x = o[0] + p[0], y = o[1] + p[1];
  const ang = Math.atan2(p[1], p[0]);
  const a = [[x, y], [x - 9 * Math.cos(ang - .4), y - 9 * Math.sin(ang - .4)],
    [x - 9 * Math.cos(ang + .4), y - 9 * Math.sin(ang + .4)]];
  return a.map(q => q.join(',')).join(' ');
}
function arc(o, r, a0, a1) {
  const p0 = [o[0] + r * Math.cos(a0 * Math.PI / 180), o[1] + r * Math.sin(a0 * Math.PI / 180)];
  const p1 = [o[0] + r * Math.cos(a1 * Math.PI / 180), o[1] + r * Math.sin(a1 * Math.PI / 180)];
  return `M${p0[0]},${p0[1]} A${r},${r} 0 0 0 ${p1[0]},${p1[1]}`;
}

// ---------- ④ 系数表 ----------
const COEF_KEYS = ['Cd','Cy','CL','Croll','Cm','Cn'];
function renderCoefTable() {
  const tb = $('coefTable').querySelector('tbody');
  tb.innerHTML = '';
  state.data.testRows.forEach(r => {
    const tr = document.createElement('tr');
    const tags = (r.extrapolated ? '<span class="tag ext">外推</span>' : '')
      + (r.qPositive ? '' : '<span class="tag q0">q≤0</span>');
    tr.innerHTML = `<td>${r.t}</td><td>${r.alphaDeg}</td><td>${r.betaDeg}</td>
      <td>${r.qPa}</td><td>${r.tempK}</td>`
      + COEF_KEYS.map(k => {
        const v = r.coefficients[k];
        if (v === null || v === undefined) {
          return `<td class="nullcoef" data-key="${k}" title="q 非正，不生成系数">无</td>`;
        }
        return `<td class="coef" data-key="${k}" title="点击查看 ${k} 的贡献链">${fmt(v, 5)}</td>`;
      }).join('')
      + `<td>${tags || '—'}</td>`;
    tr.querySelectorAll('td.coef,td.nullcoef').forEach(td => {
      td.addEventListener('click', () => showCoefficientDetail(r, td.dataset.key));
    });
    tb.appendChild(tr);
  });
}

function kvBlock(title, entries) {
  return `<div class="block-title">${title}</div><div class="kv">`
    + entries.map(([k, v]) => `<div><span>${k}</span>${v}</div>`).join('') + `</div>`;
}
function matBlock(title, node) {
  const rows = node.values.map(row => row.map(v => fmt(v, 5).padStart(9)).join('  ')).join('\n');
  const shape = '[' + node.shape.join('x') + ']';
  return `<div class="block-title">${title} ${shape}</div>`
    + `<div class="matrix">${rows}</div>`;
}
function vecBlock(title, node) {
  return kvBlock(title, [node.values.map((v, i) => [`[${i}]`, fmt(v, 6)])]);
}

function showCoefficientDetail(r, key) {
  $('detailTitle').textContent = `t=${r.t}s 测点 · 系数 ${key} 的贡献回查`;
  const c = r.chain;
  const trace = r.coefficientTrace.find(t => t.key === key);
  let html = '';

  if (!trace.generated) {
    html += `<p style="color:#ff6b6b">动压 q=${r.qPa} Pa 非正：${key} <b>不生成</b>，分母会出现除零，系统按约定保留为空，绝不输出 Infinity。</p>`;
  }
  html += kvBlock('① 该系数的直接构成', [
    ['定义', trace.numeratorExpr + ' / ' + trace.denomExpr],
    ['分子（风轴）', fmt(trace.numeratorValue, 6)],
    ['风轴分量原值', fmt(trace.windComponent, 6)],
    ['分母', trace.generated ? fmt(trace.denomValue, 6) : '不计算'],
    ['q (Pa)', fmt(trace.qPa, 3)],
    ['S (m²)', trace.areaM2],
    trace.lengthM ? ['参考长度 (m)', trace.lengthM] : ['参考长度', '—'],
    ['结果', trace.generated ? fmt(r.coefficients[key], 7) : 'null'],
  ]);

  // 力系数：回查到模型力；力矩系数：额外给叉积分项
  if (trace.kind === 'moment') {
    const axisIdx = {Croll: 0, Cm: 1, Cn: 2}[key];
    const mt = r.momentShiftTrace[axisIdx];
    html += `<div class="block-title">② 力矩平移贡献（叉积，不可用加法替代）</div>
      <table class="contrib"><thead><tr><th>项</th><th>r 分量</th><th>F 分量</th><th>贡献值</th></tr></thead><tbody>`
      + mt.crossTerms.map(t => `<tr class="contrib-row"><td>${t.term}</td>
          <td>${fmt(t.r, 4)}</td><td>${fmt(t.f, 4)}</td><td>${fmt(t.value, 5)}</td></tr>`).join('')
      + `<tr><td><b>${mt.component} 合计</b></td><td colspan="2">天平中心力矩</td><td>${fmt(mt.momentAtBalance, 5)}</td></tr>`
      + `<tr><td>r×F 小计</td><td colspan="2"></td><td><b>${fmt(mt.rCrossF, 5)}</b></td></tr>`
      + `<tr><td>参考中心力矩 = 天平 + r×F</td><td colspan="2"></td><td><b>${fmt(mt.momentAtRef, 5)}</b></td></tr>`
      + `</tbody></table>`;
  }

  html += kvBlock('③ 力的变换链（数值）', [
    ['α (rad)', fmt(c.alphaRad, 6)], ['β (rad)', fmt(c.betaRad, 6)],
    ['旋转次序', c.rotationOrder],
  ]);
  html += vecLine('六通道原值 raw', c.raw)
    + vecLine('漂移 drift', c.drift)
    + vecLine('零线后 zeroed=raw−drift', c.zeroed)
    + vecLine('天平坐标 w=K·zeroed', c.wBalance6)
    + vecLine('模型力 F_model=mount·wF', c.fModel)
    + vecLine('天平中心力矩(模型轴)', c.mBalanceModelAxes)
    + vecLine('rRef×F', c.rCrossF)
    + vecLine('参考中心力矩 M_ref', c.mRef)
    + vecLine('风轴力 F_wind=R_w·F', c.fWind)
    + vecLine('风轴力矩 M_wind=R_w·M_ref', c.mWind);

  html += matBlock('K（标定 6×6）', c.K)
    + matBlock('mount（安装 3×3）', c.mount)
    + matBlock('Ry(−α)', c.RyNegAlpha)
    + matBlock('Rz(β)', c.RzBeta)
    + matBlock('R_w（' + c.rotationOrder + ' 次序）', c.RWind);

  if (r.diagnostics.length) {
    html += `<div class="block-title">④ 诊断</div><ul>`
      + r.diagnostics.map(g => `<li style="color:#ffb454">${g}</li>`).join('') + `</ul>`;
  }
  $('detailBody').innerHTML = html;
  $('detailDialog').showModal();
}
function vecLine(title, node) {
  return `<div class="block-title">${title}</div><div class="matrix">[${
    node.values.map(v => fmt(v, 6)).join(', ')}]</div>`;
}

// ---------- 渲染入口 ----------
function render() {
  renderTareControls();
  renderSequence();
  renderChannels();
  renderAxes();
  renderCoefTable();
}

// ---------- 事件 ----------
$('runSelect').addEventListener('change', async () => {
  state.runId = Number($('runSelect').value);
  syncControlsFromRun();
  await refresh();
});
['driftMode','rotationOrder','calibVersion'].forEach(id =>
  $(id).addEventListener('change', () => refresh().catch(e => banner(e.message, false))));

$('applyBtn').addEventListener('click', async () => {
  try {
    await api(`/api/runs/${state.runId}/settings`, {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        driftMode: $('driftMode').value,
        rotationOrder: $('rotationOrder').value,
        calibVersion: $('calibVersion').value,
      }),
    });
    await loadState();
    $('runSelect').value = state.runId;
    await refresh();
    banner('已保存为本 run 的设置并记入操作记录', true);
  } catch (e) { banner(e.message, false); }
});

$('exportBtn').addEventListener('click', () => { window.location.href = '/api/export'; });

$('importFile').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  try {
    const snapshot = JSON.parse(await file.text());
    const res = await api('/api/import', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({clear: $('importClear').checked, snapshot}),
    });
    await loadState();
    await refresh();
    banner(`导入成功：${res.importedSamples} 条记录`, true);
  } catch (err) { banner('导入失败：' + err.message, false); }
  e.target.value = '';
});

$('resetBtn').addEventListener('click', async () => {
  if (!confirm('清空当前数据库并恢复内置 fixture？')) return;
  await api('/api/reset', {method: 'POST'});
  await loadState();
  await refresh();
  banner('已恢复内置 fixture', true);
});

$('detailClose').addEventListener('click', () => $('detailDialog').close());

(async function init() {
  try {
    await loadState();
    await refresh();
  } catch (e) {
    banner('初始化失败：' + e.message, false);
  }
})();
