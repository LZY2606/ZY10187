let state = null;
let selected = { sampleId: null, coefficientIndex: null };

const channelNames = ['Fx', 'Fy', 'Fz', 'Mx', 'My', 'Mz'];
const coefficientNames = ['CX', 'CY', 'CZ', 'Cl', 'Cm', 'Cn'];
const channelColors = ['#2f6f7e', '#b85c38', '#5f7d38', '#8c4f7c', '#b49132', '#495b91'];

const el = {
  runSelect: document.getElementById('runSelect'),
  rotationSelect: document.getElementById('rotationSelect'),
  calibrationSelect: document.getElementById('calibrationSelect'),
  notice: document.getElementById('notice'),
  timelineSvg: document.getElementById('timelineSvg'),
  channelsSvg: document.getElementById('channelsSvg'),
  anglesSvg: document.getElementById('anglesSvg'),
  sampleTable: document.getElementById('sampleTable'),
  explanations: document.getElementById('explanations'),
  detailPanel: document.getElementById('detailPanel')
};

document.addEventListener('DOMContentLoaded', async () => {
  bindEvents();
  await loadState();
});

function bindEvents() {
  el.runSelect.addEventListener('change', async () => {
    selected = { sampleId: null, coefficientIndex: null };
    await loadState();
  });
  el.rotationSelect.addEventListener('change', saveConfig);
  el.calibrationSelect.addEventListener('change', saveConfig);
  document.querySelectorAll('input[name="driftMode"]').forEach(radio => radio.addEventListener('change', saveConfig));
  document.getElementById('exportBtn').addEventListener('click', exportData);
  document.getElementById('resetBtn').addEventListener('click', resetFixture);
  document.getElementById('importFile').addEventListener('change', importData);
  document.addEventListener('click', async event => {
    const button = event.target.closest('[data-tare]');
    if (!button) return;
    await sendJson('/api/samples/' + button.dataset.tare, {
      tareAccepted: button.dataset.accepted === 'true'
    }, 'PATCH');
    await loadState();
  });
}

async function loadState() {
  const params = new URLSearchParams();
  if (el.runSelect.value) params.set('runId', el.runSelect.value);
  const response = await fetch('/api/state' + (params.size ? '?' + params : ''));
  if (!response.ok) throw new Error(await response.text());
  state = await response.json();
  render();
}

function render() {
  renderControls();
  renderNotice();
  renderTimeline();
  renderChannels();
  renderAngles();
  renderTable();
  renderExplanations();
  renderDetail();
}

async function saveConfig() {
  const driftMode = document.querySelector('input[name="driftMode"]:checked').value;
  await sendJson('/api/runs/' + state.selectedRunId + '/config', {
    driftMode,
    rotationOrder: el.rotationSelect.value,
    calibrationId: el.calibrationSelect.value
  }, 'POST');
  await loadState();
}

async function sendJson(url, body, method) {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({ error: response.statusText }));
    throw new Error(payload.error || '请求失败');
  }
}

function samples() {
  return state.analysis.samples;
}

function renderControls() {
  if (el.runSelect.options.length !== state.runs.length) {
    el.runSelect.innerHTML = state.runs.map(run => `<option value="${run.id}">${run.id} · ${run.name}</option>`).join('');
  }
  el.runSelect.value = state.selectedRunId;

  el.calibrationSelect.innerHTML = state.calibrations.map(calibration =>
    `<option value="${calibration.id}">${calibration.label}</option>`).join('');
  const config = state.analysis.config;
  el.calibrationSelect.value = config.calibrationId;
  el.rotationSelect.value = config.rotationOrder;
  document.querySelectorAll('input[name="driftMode"]').forEach(radio => {
    radio.checked = radio.value === config.driftMode;
  });
}

function renderNotice() {
  const extrapolated = samples().some(sample => sample.extrapolated && sample.kind !== 'TARE');
  const noTare = samples().some(sample => sample.diagnostics.includes('NO_ACCEPTED_TARE'));
  const qZero = samples().some(sample => sample.diagnostics.includes('DYNAMIC_PRESSURE_NONPOSITIVE'));
  const messages = [];
  if (extrapolated) messages.push('存在测点超出已接受空载锨点包络：图中以橙色标出，分段模式保持最近端点值，不借用其他 run。');
  if (noTare) messages.push('当前 run 没有已接受空载锨点：漂移估计为零，并标记 NO_ACCEPTED_TARE。');
  if (qZero) messages.push('存在 q ≤ 0 测点：系数留空，原始力和中间向量仍完整保留。');
  el.notice.classList.toggle('hidden', messages.length === 0);
  el.notice.textContent = messages.join(' ');
}

function extent(values) {
  let min = Math.min(...values);
  let max = Math.max(...values);
  if (min === max) {
    min -= 1;
    max += 1;
  }
  const padding = (max - min) * 0.08;
  return [min - padding, max + padding];
}

function scaleFactory(domainMin, domainMax, rangeMin, rangeMax) {
  return value => rangeMin + (value - domainMin) * (rangeMax - rangeMin) / (domainMax - domainMin);
}

function svgEl(name, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', name);
  Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
  return node;
}

function renderSvgFrame(svg, xScale, yScale, yMin, yMax, yLabel) {
  svg.innerHTML = '';
  const width = 900;
  const height = Number(svg.viewBox.baseVal.height);
  const left = 58, right = 18, top = 20, bottom = height - 32;
  const plotHeight = bottom - top;
  const ticks = 4;
  for (let i = 0; i <= ticks; i++) {
    const value = yMin + (yMax - yMin) * i / ticks;
    const y = top + plotHeight * (1 - i / ticks);
    svg.appendChild(svgEl('line', { x1: left, y1: y, x2: width - right, y2: y, stroke: '#e7dfcf' }));
    const text = svgEl('text', { x: 8, y: y + 4, 'font-size': 10, fill: '#6e6558' });
    text.textContent = fmt(value);
    svg.appendChild(text);
  }
  svg.appendChild(svgEl('line', { x1: left, y1: bottom, x2: width - right, y2: bottom, stroke: '#756b5b' }));
  const label = svgEl('text', { x: left, y: 14, 'font-size': 11, fill: '#5b5349' });
  label.textContent = yLabel;
  svg.appendChild(label);
  return { left, right, top, bottom, width, height };
}

function renderTimeline() {
  const svg = el.timelineSvg;
  const times = samples().map(sample => sample.elapsedSeconds);
  const min = Math.min(...times);
  const max = Math.max(...times);
  const x = scaleFactory(min, max, 58, 882);
  svg.innerHTML = '';
  svg.appendChild(svgEl('line', { x1: 58, y1: 78, x2: 882, y2: 78, stroke: '#756b5b', 'stroke-width': 2 }));
  samples().forEach(sample => {
    const cx = x(sample.elapsedSeconds);
    const isTare = sample.kind === 'TARE';
    const color = isTare ? (sample.tareAccepted ? '#8d6b28' : '#a8a29a') :
      sample.extrapolated ? '#c65c2e' : sample.dynamicPressure <= 0 ? '#777777' : '#47745c';
    const group = svgEl('g', { class: 'timeline-point', tabindex: 0 });
    group.style.cursor = 'pointer';
    group.appendChild(svgEl('circle', {
      cx, cy: 78, r: selected.sampleId === sample.sampleId ? 10 : 7,
      fill: color, stroke: '#27352a', 'stroke-width': 1
    }));
    const text = svgEl('text', { x: cx, y: isTare ? 45 : 112, 'text-anchor': 'middle', 'font-size': 11, fill: '#3a332b' });
    text.textContent = `${sample.elapsedSeconds}s ${isTare ? (sample.tareAccepted ? '空载' : '拒绝') : '测'}`;
    group.appendChild(text);
    group.addEventListener('click', () => {
      selected.sampleId = sample.sampleId;
      render();
    });
    svg.appendChild(group);
  });
  const tare = samples().filter(sample => sample.kind === 'TARE' && sample.tareAccepted);
  if (tare.length) {
    const envelope = svgEl('rect', {
      x: x(tare[0].elapsedSeconds), y: 22, width: x(tare[tare.length - 1].elapsedSeconds) - x(tare[0].elapsedSeconds),
      height: 112, fill: '#8d6b28', opacity: 0.08
    });
    envelope.setAttribute('pointer-events', 'none');
    svg.insertBefore(envelope, svg.firstChild);
  }
  document.getElementById('legend').innerHTML = `
    <span style="--c:#8d6b28;color:#8d6b28">已接受空载</span>
    <span style="--c:#a8a29a;color:#a8a29a">被拒绝空载</span>
    <span style="--c:#47745c;color:#47745c">正常测点</span>
    <span style="--c:#777777;color:#777777">q 非正</span>
    <span style="--c:#c65c2e;color:#c65c2e">包络外/外推</span>`;
}

function renderChannels() {
  const svg = el.channelsSvg;
  const allValues = samples().flatMap(sample => [...sample.rawChannels, ...sample.driftEstimate]);
  const [yMin, yMax] = extent(allValues);
  const x = scaleFactory(Math.min(...samples().map(s => s.elapsedSeconds)),
    Math.max(...samples().map(s => s.elapsedSeconds)), 58, 882);
  const y = scaleFactory(yMin, yMax, 228, 28);
  const frame = renderSvgFrame(svg, x, y, yMin, yMax, '通道原值 / 空载估计');
  for (let channel = 0; channel < 6; channel++) {
    const rawPoints = samples().map(sample => `${x(sample.elapsedSeconds)},${y(sample.rawChannels[channel])}`).join(' ');
    svg.appendChild(svgEl('polyline', {
      points: rawPoints, fill: 'none', stroke: channelColors[channel], 'stroke-width': 2
    }));
    const tareSamples = samples().filter(sample => sample.kind === 'TARE' && sample.tareAccepted);
    const estimated = samples().map(sample => `${x(sample.elapsedSeconds)},${y(sample.driftEstimate[channel])}`).join(' ');
    svg.appendChild(svgEl('polyline', {
      points: estimated, fill: 'none', stroke: channelColors[channel],
      'stroke-width': 1.5, 'stroke-dasharray': '5 4', opacity: 0.75
    }));
    tareSamples.forEach(sample => svg.appendChild(svgEl('rect', {
      x: x(sample.elapsedSeconds) - 4, y: y(sample.rawChannels[channel]) - 4,
      width: 8, height: 8, fill: channelColors[channel], stroke: '#3c352d'
    })));
  }
  samples().forEach(sample => {
    if (sample.extrapolated) {
      svg.appendChild(svgEl('text', {
        x: x(sample.elapsedSeconds), y: 20, 'text-anchor': 'middle',
        'font-size': 11, fill: '#c65c2e', 'font-weight': 700
      })).textContent = '外推';
    }
  });
  const keyText = svgEl('text', { x: 60, y: 248, 'font-size': 12, fill: '#5b5349' });
  keyText.textContent = '实线=六通道原值，虚线=漂移估计；方块=已接受空载锨点';
  svg.appendChild(keyText);
}

function renderAngles() {
  const svg = el.anglesSvg;
  svg.innerHTML = '';
  const minTime = Math.min(...samples().map(s => s.elapsedSeconds));
  const maxTime = Math.max(...samples().map(s => s.elapsedSeconds));
  const x = scaleFactory(minTime, maxTime, 58, 882);
  const plots = [
    { key: 'alphaRadians', name: 'α', unit: '°', color: '#47745c', convert: value => value * 180 / Math.PI, top: 16 },
    { key: 'betaRadians', name: 'β', unit: '°', color: '#8c4f7c', convert: value => value * 180 / Math.PI, top: 76 },
    { key: 'dynamicPressure', name: 'q', unit: 'Pa', color: '#b85c38', convert: value => value, top: 136 },
    { key: 'temperatureCelsius', name: 'T', unit: '℃', color: '#b49132', convert: value => value, top: 136 }
  ];
  const rowPlots = [
    [plots[0], plots[1]],
    [plots[2]],
    [plots[3]]
  ];
  rowPlots.forEach((row, rowIndex) => {
    const top = 12 + rowIndex * 66;
    const bottom = top + 48;
    const values = row.flatMap(plot => samples().map(sample => plot.convert(sample[plot.key])));
    const [min, max] = extent(values);
    const y = scaleFactory(min, max, bottom, top);
    svg.appendChild(svgEl('line', { x1: 58, y1: bottom, x2: 882, y2: bottom, stroke: '#ddd4c4' }));
    row.forEach(plot => {
      const points = samples().map(sample => `${x(sample.elapsedSeconds)},${y(plot.convert(sample[plot.key]))}`).join(' ');
      svg.appendChild(svgEl('polyline', { points, fill: 'none', stroke: plot.color, 'stroke-width': 2 }));
      const label = svgEl('text', { x: 8, y: top + 10, 'font-size': 11, fill: plot.color });
      label.textContent = `${plot.name}(${plot.unit})`;
      svg.appendChild(label);
      samples().forEach(sample => svg.appendChild(svgEl('circle', {
        cx: x(sample.elapsedSeconds), cy: y(plot.convert(sample[plot.key])),
        r: 3, fill: plot.color
      })));
    });
  });
}

function renderTable() {
  const table = el.sampleTable;
  table.tHead.innerHTML = `<tr>
    <th>时间</th><th>类型/备注</th>
    ${channelNames.map(name => `<th>${name}原始</th>`).join('')}
    <th>q</th>${coefficientNames.map(name => `<th>${name}</th>`).join('')}
    <th>诊断</th><th>空载</th>
  </tr>`;
  table.tBodies[0].innerHTML = samples().map(sample => {
    const coefficientCells = sample.coefficients.map((value, index) =>
      `<td><button class="coeff ${value === null ? 'null' : ''}" data-sample="${sample.sampleId}" data-coeff="${index}">${value === null ? '—' : fmt(value)}</button></td>`).join('');
    return `<tr class="${selected.sampleId === sample.sampleId ? 'selected' : ''} ${sample.extrapolated && sample.kind !== 'TARE' ? 'extrapolated' : ''}">
      <td>${sample.elapsedSeconds}s</td>
      <td>${kindLabel(sample)}<br><small>${sample.note || ''}</small></td>
      ${sample.rawChannels.map(value => `<td>${fmt(value)}</td>`).join('')}
      <td>${fmt(sample.dynamicPressure)}</td>
      ${coefficientCells}
      <td>${sample.diagnostics.map(diagnostic => `<span class="badge ${diagnostic === 'DYNAMIC_PRESSURE_NONPOSITIVE' || diagnostic === 'TARE_EXTRAPOLATED' ? 'bad' : ''}">${diagnostic}</span>`).join('')}</td>
      <td>${sample.kind === 'TARE' ? tareButton(sample) : ''}</td>
    </tr>`;
  }).join('');
  table.querySelectorAll('.coeff').forEach(button => button.addEventListener('click', () => {
    selected.sampleId = Number(button.dataset.sample);
    selected.coefficientIndex = Number(button.dataset.coeff);
    renderDetail();
    document.querySelector('#detailPanel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }));
}

function kindLabel(sample) {
  if (sample.kind === 'TARE') return sample.tareAccepted ? '空载锨点' : '空载（已拒绝）';
  return '试验测点';
}

function tareButton(sample) {
  return `<button type="button" data-tare="${sample.sampleId}" data-accepted="${sample.tareAccepted ? 'false' : 'true'}">
    ${sample.tareAccepted ? '拒绝锨点' : '重新接受'}
  </button>`;
}

function renderExplanations() {
  el.explanations.innerHTML = state.analysis.explanations.map(item =>
    `<div class="explanation"><strong>${item.title}</strong><span>${item.description}</span></div>`).join('');
}

function renderDetail() {
  const sample = samples().find(item => item.sampleId === selected.sampleId);
  if (!sample || selected.coefficientIndex === null) {
    el.detailPanel.innerHTML = '<h2>系数贡献</h2><p class="hint">点击任一系数按钮，查看该系数的来源、叉积分量与矩阵链。</p>';
    return;
  }
  const detail = sample.coefficientDetails[selected.coefficientIndex];
  const vectorTitles = [
    ['rawChannels', '六通道原值'], ['driftEstimate', '漂移估计'], ['correctedChannels', '校正通道'],
    ['balanceLoad', '天平坐标'], ['modelLoad', '模型坐标'], ['crossProductMoment', 'r×F'],
    ['translatedLoad', '平移后'], ['windLoad', '风轴']
  ];
  const matrixTitles = [
    ['balanceMatrix', 'G：6×6 标定'],
    ['balanceToModelRotation', 'B：天平→模型'],
    ['translationMatrix', 'T：力矩平移'],
    ['modelToWindRotation', 'W：模型→风轴']
  ];
  const total = sample.coefficients[selected.coefficientIndex];
  el.detailPanel.innerHTML = `
    <h2>${detail.coefficient} 贡献 · ${sample.elapsedSeconds}s</h2>
    <p class="hint">分母 ${fmt(detail.denominator)}；贡献项与最终值严格对应。q 非正时贡献留空，但向量保留。</p>
    <div class="detail-grid">
      ${vectorTitles.map(([key, title]) => vectorBox(title, sample[key])).join('')}
    </div>
    <h3>矩阵链</h3>
    <div class="detail-grid">
      ${matrixTitles.map(([key, title]) => `<div><h3>${title}</h3><div class="matrix">${matrixText(sample[key])}</div></div>`).join('')}
    </div>
    <h3>逐项贡献</h3>
    ${detail.contributions.map(contribution => `
      <div class="contribution-row">
        <strong>${contribution.stage}</strong>
        <span>${contribution.formula}</span>
        <span>${contribution.coefficientContribution === null ? '—' : signed(contribution.coefficientContribution)}</span>
      </div>`).join('')}
    <div class="contribution-row total">
      <strong>最终系数</strong><span>${detail.coefficient}</span><span>${total === null ? '不生成' : signed(total)}</span>
    </div>`;
}

function vectorBox(title, vector) {
  return `<div class="vector-box"><h3>${title}</h3><div class="numbers">${
    vector.map(value => `<span>${value === null ? '—' : fmt(value)}</span>`).join('')
  }</div></div>`;
}

function matrixText(matrix) {
  return matrix.map(row => row.map(value => fmt(value).padStart(10, ' ')).join(' ')).join('\n');
}

function fmt(value) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (Math.abs(value) >= 1000 || (Math.abs(value) > 0 && Math.abs(value) < 0.001)) {
    return value.toExponential(3);
  }
  return value.toFixed(4);
}

function signed(value) {
  return (value >= 0 ? '+' : '') + fmt(value);
}

async function exportData() {
  const response = await fetch('/api/export');
  const data = await response.json();
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'wind-tunnel-zero-ledger-export.json';
  link.click();
  URL.revokeObjectURL(url);
}

async function importData(event) {
  const file = event.target.files[0];
  if (!file) return;
  const text = await file.text();
  const response = await fetch('/api/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: text
  });
  if (!response.ok) {
    alert('导入失败：' + await response.text());
    return;
  }
  event.target.value = '';
  selected = { sampleId: null, coefficientIndex: null };
  el.runSelect.innerHTML = '';
  await loadState();
}

async function resetFixture() {
  if (!confirm('将清空当前数据库并恢复固定 fixture，是否继续？')) return;
  await sendJson('/api/admin/reset-fixture', {}, 'POST');
  selected = { sampleId: null, coefficientIndex: null };
  el.runSelect.innerHTML = '';
  await loadState();
}
