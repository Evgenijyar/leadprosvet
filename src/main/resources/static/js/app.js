const state = {
  fields: [],
  provider: 'openai',
  drag: null,
  serviceEnabled: true,
  proxy: {
    enabled: false,
    url: ''
  },
  llmModels: [],
  activeLlmModelId: null,
  selectedLlmModelId: null,
  queueTimer: null,
  saveProxyTimer: null
};

const defaultPromptHtml = `
<p>Собери краткую справку о компании для первого звонка менеджера.</p>
<p>Данные из Bitrix24:</p>
<p>Компания: ${tokenHtml({ id: 'COMPANY_TITLE', label: 'Компания' })}</p>
<p>Сайт: ${tokenHtml({ id: 'WEB', label: 'Сайт' })}</p>
<p>ИНН: ${tokenHtml({ id: 'UF_CRM_INN', label: 'ИНН' })}</p>
<p>Нужно найти и структурировать: чем занимается компания, ключевые продукты/услуги, размер и география, если доступно, что важно знать перед первым звонком, возможные боли и первый заход для разговора.</p>`;

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('promptEditor').innerHTML = defaultPromptHtml;
  bindTabs();
  bindServiceSwitch();
  bindProxyHeader();
  bindProviderSwitch();
  bindPromptEditor();
  bindButtons();
  bindQueuePage();
  ensureDefaultLlmModels();
  renderLlmModelList();
  fillModelEditorFromState();
  loadFields().then(loadCurrentSettings);
  updateSerializedPrompt();
  startQueuePolling();
});

function bindTabs() {
  document.querySelectorAll('.tab-button').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('.tab-button').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      button.classList.add('active');
      document.getElementById(`tab-${button.dataset.tab}`).classList.add('active');
      if (button.dataset.tab === 'queue') refreshRealtimeQueue();
    });
  });
}

function bindServiceSwitch() {
  const checkbox = document.getElementById('serviceEnabled');
  if (!checkbox) return;

  checkbox.addEventListener('change', async () => {
    const enabled = checkbox.checked;
    setServiceEnabled(enabled);
    await saveServiceEnabled(enabled);
  });

  updateServiceToggleView();
}

function setServiceEnabled(enabled) {
  state.serviceEnabled = enabled !== false;
  const checkbox = document.getElementById('serviceEnabled');
  if (checkbox) checkbox.checked = state.serviceEnabled;
  updateServiceToggleView();
}

function updateServiceToggleView() {
  const card = document.getElementById('serviceToggleCard');
  const title = document.getElementById('serviceToggleTitle');
  const hint = document.getElementById('serviceToggleHint');
  if (!card || !title || !hint) return;

  card.classList.toggle('disabled', !state.serviceEnabled);
  title.textContent = state.serviceEnabled ? 'Сервис включён' : 'Сервис выключен';
  hint.textContent = state.serviceEnabled ? 'Вебхуки обрабатываются' : 'Обработка приостановлена';
}

async function saveServiceEnabled(enabled) {
  try {
    const response = await fetch('/api/settings/service-enabled', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.ok === false) {
      throw new Error(result.message || `HTTP ${response.status}`);
    }
    showToast(enabled ? 'Сервис включён: вебхуки будут обрабатываться' : 'Сервис выключен: вебхуки будут игнорироваться');
  } catch (error) {
    setServiceEnabled(!enabled);
    showToast('Не удалось изменить состояние сервиса: ' + error.message);
  }
}

function bindProxyHeader() {
  const checkbox = document.getElementById('useProxy');
  const input = document.getElementById('proxyUrl');
  if (!checkbox || !input) return;

  checkbox.addEventListener('change', () => {
    state.proxy.enabled = checkbox.checked;
    input.disabled = !checkbox.checked;
    updateProxyView();
    scheduleProxyAutosave();
  });

  input.addEventListener('input', () => {
    state.proxy.url = input.value;
    scheduleProxyAutosave();
  });

  updateProxyView();
}

function updateProxyView() {
  const card = document.getElementById('proxyHeaderCard');
  const checkbox = document.getElementById('useProxy');
  const input = document.getElementById('proxyUrl');
  if (checkbox) checkbox.checked = Boolean(state.proxy.enabled);
  if (input) {
    input.value = state.proxy.url || '';
    input.disabled = !state.proxy.enabled;
  }
  if (card) card.classList.toggle('enabled', Boolean(state.proxy.enabled));
}

function scheduleProxyAutosave() {
  clearTimeout(state.saveProxyTimer);
  state.saveProxyTimer = setTimeout(() => saveSettings('proxy', { quiet: true }), 650);
}

function bindProviderSwitch() {
  document.querySelectorAll('.provider-button').forEach(button => {
    button.addEventListener('click', () => {
      const model = selectedLlmModel();
      if (!model) return;
      saveCurrentModelFormToState();
      const previousProvider = model.provider;
      model.provider = normalizeProvider(button.dataset.provider);
      applyProviderDefaultsForSwitch(model, previousProvider);
      state.provider = model.provider;
      updateProviderButtons();
      fillModelEditorFromState();
      renderLlmModelList();
    });
  });
}

function bindPromptEditor() {
  const editor = document.getElementById('promptEditor');

  editor.addEventListener('pointerup', event => {
    if (!event.target.closest('.token-remove')) {
      updateSerializedPrompt();
    }
  });

  editor.addEventListener('keyup', updateSerializedPrompt);
  editor.addEventListener('input', updateSerializedPrompt);
  editor.addEventListener('paste', event => {
    event.preventDefault();
    const text = event.clipboardData?.getData('text/plain') || '';
    insertPlainTextAtCaret(text);
    updateSerializedPrompt();
  });

  editor.addEventListener('click', event => {
    const remove = event.target.closest('.token-remove');
    if (!remove) return;
    event.preventDefault();
    const token = remove.closest('.prompt-token');
    if (!token) return;
    token.remove();
    renderFields();
    updateSerializedPrompt();
  });
}

function bindButtons() {
  document.getElementById('reloadFieldsBtn').addEventListener('click', loadFields);
  document.getElementById('fieldSearch').addEventListener('input', renderFields);
  document.getElementById('resetPromptBtn').addEventListener('click', () => {
    document.getElementById('promptEditor').innerHTML = defaultPromptHtml;
    renderFields();
    updateSerializedPrompt();
  });
  document.getElementById('saveIntegrationBtn').addEventListener('click', () => saveSettings('integration'));
  document.getElementById('saveLlmBtn').addEventListener('click', () => saveSettings('llm'));
  document.getElementById('applyWebhookModeBtn')?.addEventListener('click', applyWebhookMode);
  document.getElementById('addApiKeyBtn')?.addEventListener('click', () => {
    const model = selectedLlmModel();
    if (!model) return;
    saveCurrentModelFormToState();
    model.apiKeys = cleanApiKeys([...profileApiKeys(model), '']);
    renderApiKeyInputs(model.apiKeys);
    renderLlmModelList();
  });
  document.getElementById('addLlmModelBtn')?.addEventListener('click', addLlmModel);
  document.getElementById('llmProfileName')?.addEventListener('input', () => {
    saveCurrentModelFormToState();
    renderLlmModelList();
  });
  document.getElementById('endpointUrl')?.addEventListener('input', saveCurrentModelFormToState);
  document.getElementById('modelId')?.addEventListener('input', () => {
    saveCurrentModelFormToState();
    renderLlmModelList();
  });

  document.addEventListener('pointermove', onPointerMove);
  document.addEventListener('pointerup', onPointerUp);
  document.addEventListener('pointercancel', cancelDrag);
}

async function loadFields() {
  try {
    const response = await fetch('/api/bitrix/lead-fields', { cache: 'no-store' });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error(payload?.error || `HTTP ${response.status}`);
    }
    if (!Array.isArray(payload)) {
      throw new Error(payload?.error || 'Bitrix вернул не список полей');
    }
    state.fields = payload;
    renderFields();
    renderQueueFieldSelectors();
  } catch (error) {
    state.fields = [];
    renderFields();
    renderQueueFieldSelectors();
    showToast('Не удалось загрузить реальные поля лида из Bitrix24: ' + error.message);
  }
}

function renderFields() {
  const query = document.getElementById('fieldSearch').value.trim().toLowerCase();
  const list = document.getElementById('fieldList');
  list.innerHTML = '';

  state.fields
    .filter(field => !query || `${field.id} ${field.label} ${field.group}`.toLowerCase().includes(query))
    .forEach(field => {
      const chip = document.createElement('div');
      chip.className = 'field-chip';
      chip.dataset.fieldId = field.id;
      chip.dataset.fieldLabel = field.label;
      chip.innerHTML = chipInnerHtml(field);
      chip.addEventListener('pointerdown', event => startChipDrag(event, field, chip));
      chip.addEventListener('click', event => {
        if (state.drag?.moved) return;
        event.preventDefault();
        document.getElementById('promptEditor').focus();
        insertPromptToken(field);
        renderFields();
        updateSerializedPrompt();
      });
      list.appendChild(chip);
    });
}

function startChipDrag(event, field, sourceChip) {
  if (event.button !== 0) return;
  event.preventDefault();

  const clone = document.getElementById('dragClone');
  clone.innerHTML = chipInnerHtml(field);
  clone.classList.add('active');

  state.drag = {
    field,
    sourceChip,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false
  };

  sourceChip.classList.add('drag-source');
  sourceChip.setPointerCapture?.(event.pointerId);
  moveDragClone(event.clientX, event.clientY);
}

function onPointerMove(event) {
  if (!state.drag) return;
  const dx = Math.abs(event.clientX - state.drag.startX);
  const dy = Math.abs(event.clientY - state.drag.startY);
  if (dx > 3 || dy > 3) state.drag.moved = true;

  moveDragClone(event.clientX, event.clientY);

  const editor = document.getElementById('promptEditor');
  const underPointer = document.elementFromPoint(event.clientX, event.clientY);
  editor.classList.toggle('drag-over', Boolean(underPointer && editor.contains(underPointer)));
}

function onPointerUp(event) {
  if (!state.drag) return;

  const drag = state.drag;
  const editor = document.getElementById('promptEditor');
  const underPointer = document.elementFromPoint(event.clientX, event.clientY);
  const droppedIntoEditor = Boolean(underPointer && editor.contains(underPointer));

  if (droppedIntoEditor && drag.moved) {
    editor.focus();
    placeCaretFromPoint(event.clientX, event.clientY);
    insertPromptToken(drag.field);
    renderFields();
    updateSerializedPrompt();
  }

  cancelDrag();
}

function cancelDrag() {
  const editor = document.getElementById('promptEditor');
  editor?.classList.remove('drag-over');

  const clone = document.getElementById('dragClone');
  if (clone) {
    clone.classList.remove('active');
    clone.style.transform = 'translate3d(-9999px, -9999px, 0)';
    clone.innerHTML = '';
  }

  if (state.drag?.sourceChip) {
    state.drag.sourceChip.classList.remove('drag-source');
  }

  state.drag = null;
}

function moveDragClone(x, y) {
  const clone = document.getElementById('dragClone');
  clone.style.transform = `translate3d(${x + 12}px, ${y + 12}px, 0)`;
}

function placeCaretFromPoint(x, y) {
  let range = null;
  if (document.caretRangeFromPoint) {
    range = document.caretRangeFromPoint(x, y);
  } else if (document.caretPositionFromPoint) {
    const pos = document.caretPositionFromPoint(x, y);
    range = document.createRange();
    range.setStart(pos.offsetNode, pos.offset);
  }
  if (!range) return;

  const token = range.startContainer?.parentElement?.closest?.('.prompt-token');
  if (token) range.setStartAfter(token);

  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}

function insertPromptToken(field) {
  const editor = document.getElementById('promptEditor');
  editor.focus();

  const selection = window.getSelection();
  let range;
  if (selection.rangeCount) {
    range = selection.getRangeAt(0);
  } else {
    range = document.createRange();
    range.selectNodeContents(editor);
    range.collapse(false);
  }

  if (!editor.contains(range.commonAncestorContainer)) {
    range.selectNodeContents(editor);
    range.collapse(false);
  }

  const temp = document.createElement('span');
  temp.innerHTML = tokenHtml(field);
  const token = temp.firstElementChild;
  const space = document.createTextNode(' ');

  range.deleteContents();
  range.insertNode(space);
  range.insertNode(token);
  range.setStartAfter(space);
  range.setEndAfter(space);
  selection.removeAllRanges();
  selection.addRange(range);
}

function insertPlainTextAtCaret(text) {
  const editor = document.getElementById('promptEditor');
  editor.focus();
  const selection = window.getSelection();
  let range;
  if (selection.rangeCount) {
    range = selection.getRangeAt(0);
  } else {
    range = document.createRange();
    range.selectNodeContents(editor);
    range.collapse(false);
  }
  if (!editor.contains(range.commonAncestorContainer)) {
    range.selectNodeContents(editor);
    range.collapse(false);
  }
  range.deleteContents();
  const normalized = String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const fragment = document.createDocumentFragment();
  normalized.split('\n').forEach((line, index) => {
    if (index > 0) fragment.appendChild(document.createElement('br'));
    if (line) fragment.appendChild(document.createTextNode(line));
  });
  range.insertNode(fragment);
  range.collapse(false);
  selection.removeAllRanges();
  selection.addRange(range);
}

function usedFieldIds() {
  return new Set(
    [...document.querySelectorAll('#promptEditor .prompt-token')]
      .map(token => token.dataset.fieldId)
      .filter(Boolean)
  );
}

function tokenHtml(field) {
  return `<span class="prompt-token" contenteditable="false" data-field-id="${escapeHtml(field.id)}" data-field-label="${escapeHtml(field.label)}"><span class="token-label">${escapeHtml(field.label)}</span><small>${escapeHtml(field.id)}</small><button class="token-remove" type="button" title="Удалить поле" aria-label="Удалить поле">×</button></span>`;
}

function chipInnerHtml(field) {
  return `<span>${escapeHtml(field.label)}</span><small>${escapeHtml(field.id)}</small>`;
}

function serializePrompt() {
  const editor = document.getElementById('promptEditor');
  const clone = editor.cloneNode(true);
  clone.querySelectorAll('.prompt-token').forEach(token => {
    const text = document.createTextNode(`{{${token.dataset.fieldId}}}`);
    token.replaceWith(text);
  });
  return htmlToPlainTextPreservingBreaks(clone);
}

function htmlToPlainTextPreservingBreaks(root) {
  let output = '';
  const blockTags = new Set(['DIV', 'P', 'SECTION', 'ARTICLE', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6']);

  function walk(node) {
    if (node.nodeType === Node.TEXT_NODE) {
      output += node.nodeValue || '';
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const tag = node.tagName;
    if (tag === 'BR') {
      output += '\n';
      return;
    }
    const before = output.length;
    node.childNodes.forEach(walk);
    if (blockTags.has(tag) && output.length > before && !output.endsWith('\n')) {
      output += '\n';
    }
  }

  root.childNodes.forEach(walk);
  return output.replace(/\u00a0/g, '');
}

function updateSerializedPrompt() {
  const serialized = document.getElementById('serializedPrompt');
  if (serialized) serialized.value = serializePrompt();
}

async function loadCurrentSettings() {
  try {
    const response = await fetch('/api/settings/current');
    if (!response.ok) return;
    const settings = await response.json();
    if (!settings || Object.keys(settings).length === 0) return;

    setServiceEnabled(settings.serviceEnabled !== false);

    if (settings.promptTemplate) {
      document.getElementById('promptEditor').innerHTML = promptTextToHtml(String(settings.promptTemplate));
      renderFields();
    }

    setLeadTriggerEvent(settings.leadTriggerEvent || 'ONCRMLEADADD');

    loadLlmModelsFromSettings(settings);
    renderLlmModelList();
    fillModelEditorFromState();

    const proxy = settings.proxy || {};
    state.proxy = {
      enabled: Boolean(proxy.enabled),
      url: proxyUrlFromSettings(proxy)
    };
    updateProxyView();

    updateSerializedPrompt();
  } catch (error) {
    console.warn('Settings load failed', error);
  }
}

function proxyUrlFromSettings(proxy) {
  if (proxy.url) return String(proxy.url);
  const host = String(proxy.host || '').trim();
  const port = String(proxy.port || '').trim();
  const login = String(proxy.login || '').trim();
  const password = String(proxy.password || '').trim();
  if (!host || !port) return '';
  if (login) {
    return `http://${encodeURIComponent(login)}:${encodeURIComponent(password)}@${host}:${port}`;
  }
  return `${host}:${port}`;
}

function ensureDefaultLlmModels() {
  if (state.llmModels.length) return;
  const openai = createDefaultLlmModel('openai', { name: 'OpenAPI основная', active: true });
  const google = createDefaultLlmModel('google', { name: 'Google API основная' });
  state.llmModels = [openai, google];
  state.activeLlmModelId = openai.id;
  state.selectedLlmModelId = openai.id;
  state.provider = openai.provider;
}

function createDefaultLlmModel(provider, overrides = {}) {
  const normalized = normalizeProvider(provider);
  const defaults = defaultProviderProfile(normalized);
  return {
    id: overrides.id || newModelId(),
    name: overrides.name || (normalized === 'google' ? 'Google API модель' : 'OpenAPI модель'),
    provider: normalized,
    endpointUrl: defaults.endpointUrl,
    modelId: defaults.modelId,
    apiKey: '',
    apiKeys: [''],
    active: Boolean(overrides.active)
  };
}

function loadLlmModelsFromSettings(settings) {
  const models = [];
  const rawModels = Array.isArray(settings.llmModels) ? settings.llmModels : [];

  rawModels.forEach((item, index) => {
    const normalized = cleanLlmModel(item, index);
    if (normalized) models.push(normalized);
  });

  if (!models.length) {
    const savedProfiles = settings.llmProfiles || settings.llmByProvider || {};
    ['openai', 'google'].forEach(provider => {
      const saved = savedProfiles[provider] || {};
      const hasAnyData = Boolean(saved.endpointUrl || saved.modelId || saved.apiKey || saved.apiKeys);
      if (hasAnyData) {
        models.push(cleanLlmModel({
          ...saved,
          id: `profile-${provider}`,
          name: provider === 'google' ? 'Google API основная' : 'OpenAPI основная',
          provider
        }, models.length));
      }
    });
  }

  const oldLlm = settings.llm || {};
  if (!models.length && (oldLlm.endpointUrl || oldLlm.modelId || oldLlm.apiKey || oldLlm.apiKeys)) {
    models.push(cleanLlmModel({
      ...oldLlm,
      id: 'profile-current',
      name: oldLlm.provider === 'google' ? 'Google API основная' : 'OpenAPI основная'
    }, 0));
  }

  state.llmModels = models.filter(Boolean);
  ensureDefaultLlmModels();

  const activeId = String(settings.activeLlmModelId || '').trim();
  const activeById = state.llmModels.find(model => model.id === activeId);
  const activeByFlag = state.llmModels.find(model => model.active);
  const active = activeById || activeByFlag || state.llmModels[0];
  setActiveLlmModel(active.id, { silent: true });
  state.selectedLlmModelId = active.id;
}

function cleanLlmModel(source, index = 0) {
  if (!source || typeof source !== 'object') return null;
  const provider = normalizeProvider(source.provider);
  const defaults = defaultProviderProfile(provider);
  const id = String(source.id || source.profileId || `model-${provider}-${index + 1}`).trim();
  const keys = apiKeysFromSavedProfile(source);
  return {
    id,
    name: nonBlank(source.name || source.title || source.displayName, provider === 'google' ? 'Google API модель' : 'OpenAPI модель'),
    provider,
    endpointUrl: nonBlank(source.endpointUrl, defaults.endpointUrl),
    modelId: nonBlank(source.modelId, defaults.modelId),
    apiKey: keys.find(Boolean) || '',
    apiKeys: keys.length ? keys : [''],
    active: Boolean(source.active)
  };
}

function addLlmModel() {
  saveCurrentModelFormToState();
  const provider = normalizeProvider(state.provider);
  const model = createDefaultLlmModel(provider, {
    name: provider === 'google' ? `Google API ${state.llmModels.filter(item => item.provider === 'google').length + 1}` : `OpenAPI ${state.llmModels.filter(item => item.provider === 'openai').length + 1}`
  });
  state.llmModels.push(model);
  state.selectedLlmModelId = model.id;
  renderLlmModelList();
  fillModelEditorFromState();
}

function selectedLlmModel() {
  ensureDefaultLlmModels();
  let model = state.llmModels.find(item => item.id === state.selectedLlmModelId);
  if (!model) {
    model = state.llmModels.find(item => item.id === state.activeLlmModelId) || state.llmModels[0];
    state.selectedLlmModelId = model.id;
  }
  return model;
}

function activeLlmModel() {
  ensureDefaultLlmModels();
  return state.llmModels.find(item => item.id === state.activeLlmModelId) || state.llmModels[0];
}

function setActiveLlmModel(modelId, options = {}) {
  const model = state.llmModels.find(item => item.id === modelId);
  if (!model) return;
  state.activeLlmModelId = model.id;
  state.provider = model.provider;
  state.llmModels.forEach(item => item.active = item.id === model.id);
  if (!options.keepSelection) {
    state.selectedLlmModelId = model.id;
  }
  if (!options.silent) {
    renderLlmModelList();
    fillModelEditorFromState();
  }
}

function renderLlmModelList() {
  const list = document.getElementById('llmModelList');
  if (!list) return;
  ensureDefaultLlmModels();
  const active = activeLlmModel();

  list.innerHTML = state.llmModels.map(model => {
    const selected = model.id === state.selectedLlmModelId;
    const isActive = model.id === active.id;
    const keys = profileApiKeys(model).filter(Boolean).length;
    return `
      <div class="llm-model-card ${selected ? 'selected' : ''} ${isActive ? 'active' : ''}" data-model-id="${escapeHtml(model.id)}">
        <label class="model-active-mark" title="Сделать активной">
          <input class="model-active-radio" type="radio" name="activeLlmModel" value="${escapeHtml(model.id)}" ${isActive ? 'checked' : ''}>
          <span></span>
        </label>
        <button class="model-main-button" type="button">
          <strong>${escapeHtml(model.name || model.modelId || 'LLM модель')}</strong>
          <small>${model.provider === 'google' ? 'Google API' : 'OpenAPI'} · ${escapeHtml(model.modelId || 'модель не указана')} · ключей: ${keys}</small>
        </button>
        <button class="model-delete-button" type="button" title="Удалить модель">×</button>
      </div>
    `;
  }).join('');

  list.querySelectorAll('.llm-model-card').forEach(card => {
    const id = card.dataset.modelId;
    card.querySelector('.model-main-button')?.addEventListener('click', () => {
      saveCurrentModelFormToState();
      state.selectedLlmModelId = id;
      renderLlmModelList();
      fillModelEditorFromState();
    });
    card.querySelector('.model-active-radio')?.addEventListener('change', async () => {
      saveCurrentModelFormToState();
      setActiveLlmModel(id, { keepSelection: false });
      renderLlmModelList();
      fillModelEditorFromState();
      await saveSettings('llm', { quiet: true });
      showToast('Активная LLM-модель изменена');
    });
    card.querySelector('.model-delete-button')?.addEventListener('click', async event => {
      event.stopPropagation();
      if (state.llmModels.length <= 1) {
        showToast('Нельзя удалить единственную модель');
        return;
      }
      const deletedActive = state.activeLlmModelId === id;
      state.llmModels = state.llmModels.filter(model => model.id !== id);
      if (deletedActive) setActiveLlmModel(state.llmModels[0].id, { silent: true });
      state.selectedLlmModelId = state.activeLlmModelId;
      renderLlmModelList();
      fillModelEditorFromState();
      await saveSettings('llm', { quiet: true });
      showToast('Модель удалена');
    });
  });
}

function saveCurrentModelFormToState() {
  const model = selectedLlmModel();
  if (!model) return;
  const apiKeys = cleanApiKeys(readApiKeysFromForm());
  model.name = document.getElementById('llmProfileName')?.value || model.name || '';
  model.endpointUrl = document.getElementById('endpointUrl')?.value || '';
  model.modelId = document.getElementById('modelId')?.value || '';
  model.apiKeys = apiKeys;
  model.apiKey = firstRealApiKey(apiKeys);
  model.active = model.id === state.activeLlmModelId;
  if (model.active) state.provider = model.provider;
}

function fillModelEditorFromState() {
  const model = selectedLlmModel();
  if (!model) return;
  state.provider = model.provider;
  setInputValue('llmProfileName', model.name);
  setInputValue('endpointUrl', model.endpointUrl);
  setInputValue('modelId', model.modelId);
  renderApiKeyInputs(profileApiKeys(model));
  updateProviderButtons();
  const title = document.getElementById('llmEditorTitle');
  if (title) title.textContent = model.id === state.activeLlmModelId ? 'Настройка активной модели' : 'Настройка модели';
}

function updateProviderButtons() {
  const model = selectedLlmModel();
  const provider = model ? model.provider : state.provider;
  document.querySelectorAll('.provider-button').forEach(button => {
    button.classList.toggle('active', normalizeProvider(button.dataset.provider) === provider);
  });
}

function serializeLlmModels() {
  saveCurrentModelFormToState();
  return state.llmModels.map(model => ({
    id: model.id,
    name: model.name || '',
    provider: normalizeProvider(model.provider),
    endpointUrl: model.endpointUrl || '',
    modelId: model.modelId || '',
    apiKey: firstRealApiKey(profileApiKeys(model)),
    apiKeys: cleanApiKeys(profileApiKeys(model)).filter(Boolean),
    apiKeyPresent: Boolean(firstRealApiKey(profileApiKeys(model))),
    apiKeyCount: cleanApiKeys(profileApiKeys(model)).filter(Boolean).length,
    active: model.id === state.activeLlmModelId
  }));
}

function serializeLlmProfiles() {
  const result = {};
  ['openai', 'google'].forEach(provider => {
    const activeForProvider = state.llmModels.find(model => model.provider === provider && model.id === state.activeLlmModelId);
    const firstForProvider = state.llmModels.find(model => model.provider === provider);
    const model = activeForProvider || firstForProvider || createDefaultLlmModel(provider);
    const keys = cleanApiKeys(profileApiKeys(model)).filter(Boolean);
    result[provider] = {
      provider,
      endpointUrl: model.endpointUrl || '',
      modelId: model.modelId || '',
      apiKey: keys[0] || '',
      apiKeys: keys,
      apiKeyPresent: keys.length > 0,
      apiKeyCount: keys.length
    };
  });
  return result;
}

function defaultProviderProfile(provider) {
  return provider === 'google'
    ? {
        provider: 'google',
        endpointUrl: 'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
        modelId: 'gemini-2.5-flash',
        apiKey: '',
        apiKeys: ['']
      }
    : {
        provider: 'openai',
        endpointUrl: 'https://api.openai.com/v1/chat/completions',
        modelId: 'gpt-4.1-mini',
        apiKey: '',
        apiKeys: ['']
      };
}

function applyProviderDefaultsForSwitch(model, previousProvider) {
  const defaults = defaultProviderProfile(model.provider);
  const previousDefaults = defaultProviderProfile(previousProvider);
  const endpoint = String(model.endpointUrl || '').trim();
  const modelId = String(model.modelId || '').trim();
  if (!endpoint || endpoint === previousDefaults.endpointUrl) model.endpointUrl = defaults.endpointUrl;
  if (!modelId || modelId === previousDefaults.modelId) model.modelId = defaults.modelId;
}

function apiKeysFromSavedProfile(profile) {
  if (Array.isArray(profile.apiKeys)) {
    const keys = profile.apiKeys.map(value => String(value || '').trim()).filter(Boolean);
    if (keys.length) return keys;
  }
  const single = String(profile.apiKey || '').trim();
  return single ? [single] : [''];
}

function profileApiKeys(profile) {
  return cleanApiKeys(profile.apiKeys || (profile.apiKey ? [profile.apiKey] : ['']));
}

function cleanApiKeys(keys) {
  const result = (Array.isArray(keys) ? keys : [''])
    .map(value => String(value || '').trim());
  return result.length ? result : [''];
}

function firstRealApiKey(keys) {
  return cleanApiKeys(keys).find(Boolean) || '';
}

function readApiKeysFromForm() {
  return [...document.querySelectorAll('#apiKeyList .api-key-input')].map(input => input.value);
}

function renderApiKeyInputs(keys) {
  const list = document.getElementById('apiKeyList');
  if (!list) return;
  const normalized = cleanApiKeys(keys);
  const rows = normalized.length ? normalized : [''];
  list.innerHTML = '';
  rows.forEach((key, index) => {
    const row = document.createElement('div');
    row.className = 'api-key-row';
    row.innerHTML = `
      <span class="api-key-number">${index + 1}</span>
      <input class="api-key-input" type="password" value="${escapeHtml(key)}" placeholder="API-ключ #${index + 1}">
      <button class="api-key-remove" type="button" title="Удалить ключ">×</button>
    `;
    row.querySelector('.api-key-input').addEventListener('input', () => {
      saveCurrentModelFormToState();
      renderLlmModelList();
    });
    row.querySelector('.api-key-remove').addEventListener('click', () => {
      const current = readApiKeysFromForm();
      current.splice(index, 1);
      const model = selectedLlmModel();
      model.apiKeys = cleanApiKeys(current.length ? current : ['']);
      model.apiKey = firstRealApiKey(model.apiKeys);
      renderApiKeyInputs(model.apiKeys);
      renderLlmModelList();
    });
    list.appendChild(row);
  });
}

function normalizeProvider(value) {
  const text = String(value || '').trim().toLowerCase();
  return text === 'google' ? 'google' : 'openai';
}

function nonBlank(value, fallback) {
  const text = value === undefined || value === null ? '' : String(value).trim();
  return text || fallback;
}

function newModelId() {
  return `llm-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}

function setInputValue(id, value) {
  const element = document.getElementById(id);
  if (element) element.value = value || '';
}

function promptTextToHtml(text) {
  const escaped = escapeHtml(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const withTokens = escaped.replace(/\{\{([A-Z0-9_]+)}}/g, (_, fieldId) => {
    const field = state.fields.find(item => item.id === fieldId) || { id: fieldId, label: fieldId };
    return tokenHtml(field);
  });
  return withTokens.replace(/\n/g, '<br>');
}

async function saveSettings(section, options = {}) {
  updateSerializedPrompt();
  saveCurrentModelFormToState();
  const activeModel = activeLlmModel();
  const activeKeys = cleanApiKeys(profileApiKeys(activeModel)).filter(Boolean);
  const llmProfiles = serializeLlmProfiles();
  const payload = {
    section,
    provider: activeModel.provider,
    serviceEnabled: state.serviceEnabled,
    activeLlmModelId: activeModel.id,
    promptTemplate: serializePrompt(),
    llmModels: serializeLlmModels(),
    llm: {
      provider: activeModel.provider,
      endpointUrl: activeModel.endpointUrl,
      modelId: activeModel.modelId,
      apiKey: activeKeys[0] || '',
      apiKeys: activeKeys,
      apiKeyPresent: activeKeys.length > 0,
      apiKeyCount: activeKeys.length
    },
    llmProfiles,
    leadTriggerEvent: currentLeadTriggerEvent(),
    proxy: {
      enabled: Boolean(state.proxy.enabled),
      url: state.proxy.url || '',
      passwordPresent: Boolean(extractProxyPasswordHint(state.proxy.url))
    }
  };

  try {
    const response = await fetch('/api/settings/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await response.json();
    if (!options.quiet) showToast(result.ok ? 'Настройки сохранены' : (result.message || 'Настройки сохранены'));
  } catch (error) {
    if (!options.quiet) showToast('Ошибка сохранения: ' + error.message);
  }
}

function extractProxyPasswordHint(url) {
  const text = String(url || '');
  if (!text.includes('@')) return '';
  const auth = text.split('@')[0].replace(/^\w+:\/\//, '');
  return auth.includes(':') ? auth.split(':').slice(1).join(':') : '';
}

async function applyWebhookMode() {
  await saveSettings('trigger', { quiet: true });
  try {
    const response = await fetch('/api/bitrix/setup/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.ok === false) {
      throw new Error(result.error || `HTTP ${response.status}`);
    }
    const bind = result.bindSelectedLeadEvent || {};
    showToast(bind.ok ? `Вебхук применён: ${bind.selectedEventLabel || bind.selectedEvent || currentLeadTriggerEvent()}` : 'Setup выполнен, но вебхук вернул предупреждение');
  } catch (error) {
    showToast('Ошибка применения вебхука: ' + error.message);
  }
}

function currentLeadTriggerEvent() {
  return document.querySelector('input[name="leadTriggerEvent"]:checked')?.value || 'ONCRMLEADADD';
}

function setLeadTriggerEvent(value) {
  const normalized = value === 'ONCRMLEADUPDATE' ? 'ONCRMLEADUPDATE' : 'ONCRMLEADADD';
  document.querySelectorAll('input[name="leadTriggerEvent"]').forEach(input => {
    input.checked = input.value === normalized;
  });
}

function bindQueuePage() {
  document.getElementById('queueFieldOne')?.addEventListener('change', () => {
    saveQueueColumnSelection();
    refreshRealtimeQueue();
  });
  document.getElementById('queueFieldTwo')?.addEventListener('change', () => {
    saveQueueColumnSelection();
    refreshRealtimeQueue();
  });
}

function renderQueueFieldSelectors() {
  const first = document.getElementById('queueFieldOne');
  const second = document.getElementById('queueFieldTwo');
  if (!first || !second) return;

  const savedOne = localStorage.getItem('leadprosvet.queueFieldOne') || 'ID';
  const savedTwo = localStorage.getItem('leadprosvet.queueFieldTwo') || 'TITLE';
  const fields = uniqueFields([
    { id: 'ID', label: 'ID лида', group: 'Системное' },
    { id: 'TITLE', label: 'Название лида', group: 'Системное' },
    ...state.fields
  ]);

  [first, second].forEach(select => {
    const current = select === first ? savedOne : savedTwo;
    select.innerHTML = fields.map(field =>
      `<option value="${escapeHtml(field.id)}">${escapeHtml(field.label || field.id)} · ${escapeHtml(field.id)}</option>`
    ).join('');
    select.value = fields.some(field => field.id === current) ? current : (select === first ? 'ID' : 'TITLE');
  });
}

function uniqueFields(fields) {
  const seen = new Set();
  return fields.filter(field => {
    if (seen.has(field.id)) return false;
    seen.add(field.id);
    return true;
  });
}

function saveQueueColumnSelection() {
  const first = document.getElementById('queueFieldOne')?.value || 'ID';
  const second = document.getElementById('queueFieldTwo')?.value || 'TITLE';
  localStorage.setItem('leadprosvet.queueFieldOne', first);
  localStorage.setItem('leadprosvet.queueFieldTwo', second);
}

function startQueuePolling() {
  clearInterval(state.queueTimer);
  state.queueTimer = setInterval(refreshRealtimeQueue, 1500);
  refreshRealtimeQueue();
}

async function refreshRealtimeQueue() {
  const body = document.getElementById('queueTableBody');
  if (!body) return;
  const first = document.getElementById('queueFieldOne')?.value || 'ID';
  const second = document.getElementById('queueFieldTwo')?.value || 'TITLE';
  try {
    const url = `/api/bitrix/queue/realtime?firstFieldId=${encodeURIComponent(first)}&secondFieldId=${encodeURIComponent(second)}`;
    const response = await fetch(url, { cache: 'no-store' });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.ok === false) throw new Error(payload.error || `HTTP ${response.status}`);
    renderQueueTable(payload.jobs || []);
    const stats = document.getElementById('queueStats');
    if (stats) {
      stats.textContent = `Ожидание: ${payload.pending || 0} · В работе: ${payload.processing || 0} · Ключей: ${payload.apiKeyCount || 0}`;
    }
  } catch (error) {
    console.warn('Realtime queue refresh failed', error);
  }
}

function renderQueueTable(jobs) {
  const body = document.getElementById('queueTableBody');
  if (!body) return;
  if (!jobs.length) {
    body.innerHTML = '<tr><td colspan="5" class="queue-empty">Очередь пока пуста</td></tr>';
    return;
  }
  body.innerHTML = jobs.map((job, index) => `
    <tr class="queue-row status-${String(job.status || '').toLowerCase()}">
      <td class="queue-num-col">${index + 1}</td>
      <td>${escapeHtml(job.firstValue || '')}</td>
      <td>${escapeHtml(job.secondValue || '')}</td>
      <td class="queue-attempt-col">${Number(job.attempt || 0) || ''}</td>
      <td class="queue-status-col"><span class="queue-status-pill">${queueStatusLabel(job.status)}</span></td>
    </tr>
  `).join('');
}

function queueStatusLabel(status) {
  const text = String(status || '').toUpperCase();
  if (text === 'PROCESSING') return 'В работе';
  if (text === 'PENDING') return 'Ожидание';
  return text || '—';
}

function showToast(message) {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 3400);
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}
