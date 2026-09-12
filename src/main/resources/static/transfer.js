(() => {
  const tokenInput = document.querySelector('#admin-token');
  const fileInput = document.querySelector('#file-input');
  const selectedFile = document.querySelector('#selected-file');
  const uploadButton = document.querySelector('#upload');
  const refreshButton = document.querySelector('#refresh');
  const listBody = document.querySelector('#file-list');
  const fileCount = document.querySelector('#file-count');
  const limitInfo = document.querySelector('#limit-info');
  const statusBox = document.querySelector('#status');
  const dropZone = document.querySelector('#drop-zone');
  const textName = document.querySelector('#text-name');
  const textContent = document.querySelector('#text-content');
  const createTextButton = document.querySelector('#create-text');
  let chosenFile = null;

  const token = () => tokenInput.value.trim();
  const authHeaders = (extra = {}) => token() ? { ...extra, Authorization: `Bearer ${token()}` } : extra;
  const setStatus = (message, kind = '') => {
    statusBox.textContent = message;
    statusBox.className = `status${kind ? ` ${kind}` : ''}`;
  };
  const formatBytes = bytes => {
    if (!Number.isFinite(bytes)) return '-';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes, unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
    return `${value >= 10 || unit === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
  };

  async function api(url, options = {}) {
    const response = await fetch(url, { ...options, headers: authHeaders(options.headers || {}) });
    if (!response.ok) {
      let detail = `${response.status} ${response.statusText}`;
      try {
        const body = await response.json();
        if (body.error) detail = body.message ? `${body.error}: ${body.message}` : body.error;
      } catch (_) {}
      throw new Error(detail);
    }
    return response;
  }

  function actionButton(label, className, handler) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.className = `small ${className || ''}`.trim();
    button.addEventListener('click', handler);
    return button;
  }

  async function refreshFiles() {
    try {
      setStatus('파일 목록을 읽는 중…');
      const data = await (await api('/api/files')).json();
      renderFiles(data.files || []);
      limitInfo.textContent = `파일당 최대 ${formatBytes(data.maxUploadBytes)} · 공개 다운로드 ${data.publicDownloads ? '허용' : '차단'}`;
      fileCount.textContent = `${(data.files || []).length} files`;
      setStatus('파일 목록을 갱신했습니다.', 'success');
    } catch (error) { setStatus(`목록 조회 실패: ${error.message}`, 'error'); }
  }

  function renderFiles(files) {
    listBody.replaceChildren();
    if (!files.length) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 5;
      cell.className = 'empty';
      cell.textContent = '저장된 파일이 없습니다.';
      row.append(cell);
      listBody.append(row);
      return;
    }
    files.forEach(file => {
      const row = document.createElement('tr');
      const name = document.createElement('td'); name.textContent = file.name;
      const size = document.createElement('td'); size.textContent = formatBytes(file.size);
      const updated = document.createElement('td'); updated.textContent = new Date(file.updatedAt).toLocaleString();
      const checksum = document.createElement('td'); checksum.className = 'checksum'; checksum.title = file.sha256; checksum.textContent = file.sha256;
      const actions = document.createElement('td'); actions.className = 'actions';
      actions.append(actionButton('다운로드', '', () => downloadFile(file)), actionButton('삭제', 'danger', () => deleteFile(file)));
      row.append(name, size, updated, checksum, actions);
      listBody.append(row);
    });
  }

  async function uploadFile(file) {
    if (!file) { setStatus('전송할 파일을 먼저 선택하세요.', 'error'); return; }
    try {
      setStatus(`${file.name} 업로드 중…`);
      await api(`/api/files/${encodeURIComponent(file.name)}`, { method: 'PUT', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
      setStatus(`${file.name} 전송 완료`, 'success');
      await refreshFiles();
    } catch (error) { setStatus(`업로드 실패: ${error.message}`, 'error'); }
  }

  async function createTextFile() {
    const name = textName.value.trim();
    if (!name) { setStatus('텍스트 파일 이름을 입력하세요.', 'error'); return; }
    try {
      setStatus(`${name} 생성 중…`);
      await api(`/api/files/${encodeURIComponent(name)}`, { method: 'PUT', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: new Blob([textContent.value], { type: 'text/plain;charset=utf-8' }) });
      setStatus(`${name} 생성 완료`, 'success');
      await refreshFiles();
    } catch (error) { setStatus(`파일 생성 실패: ${error.message}`, 'error'); }
  }

  async function downloadFile(file) {
    try {
      setStatus(`${file.name} 다운로드 준비 중…`);
      const blob = await (await api(file.downloadUrl)).blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setStatus(`${file.name} 다운로드 시작`, 'success');
    } catch (error) { setStatus(`다운로드 실패: ${error.message}`, 'error'); }
  }

  async function deleteFile(file) {
    if (!window.confirm(`${file.name} 파일을 서버에서 삭제할까요?`)) return;
    try {
      await api(`/api/files/${encodeURIComponent(file.name)}`, { method: 'DELETE' });
      setStatus(`${file.name} 삭제 완료`, 'success');
      await refreshFiles();
    } catch (error) { setStatus(`삭제 실패: ${error.message}`, 'error'); }
  }

  function choose(file) {
    chosenFile = file || null;
    selectedFile.textContent = chosenFile ? `${chosenFile.name} · ${formatBytes(chosenFile.size)}` : '선택된 파일 없음';
  }

  fileInput.addEventListener('change', () => choose(fileInput.files[0]));
  uploadButton.addEventListener('click', () => uploadFile(chosenFile));
  refreshButton.addEventListener('click', refreshFiles);
  createTextButton.addEventListener('click', createTextFile);
  ['dragenter', 'dragover'].forEach(type => dropZone.addEventListener(type, event => { event.preventDefault(); dropZone.classList.add('dragging'); }));
  ['dragleave', 'drop'].forEach(type => dropZone.addEventListener(type, event => { event.preventDefault(); dropZone.classList.remove('dragging'); }));
  dropZone.addEventListener('drop', event => choose(event.dataTransfer.files[0]));
})();
