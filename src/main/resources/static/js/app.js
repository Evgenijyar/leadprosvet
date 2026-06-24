const state = {
  fields: [],
  provider: 'openai',
  drag: null,
  serviceEnabled: true,
  llmProfiles: {
    openai: {
      endpointUrl: 'https://api.openai.com/v1/chat/completions',
      modelId: 'gpt-4.1-mini',
      apiKey: '',
      apiKeys: ['']
    },
    google: {
      endpointUrl: 'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
      modelId: 'gemini-2.5-flash',
      apiKey: '',
      apiKeys: ['']
    }
  },
  queueTimer: null
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
  bindProviderSwitch();
  bindProxySwitch();
  bindPromptEditor();
  bindButtons();
  bindQueuePage();
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
  hint.textContent = state.serviceEnabled ? 'Вебхуки обрабатываются' : 'Обработка вебхуков приостановлена';
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

function bindProviderSwitch() {
  document.querySelectorAll('.provider-button').forEach(button => {
    button.addEventListener('click', () => {
      const nextProvider = normalizeProvider(button.dataset.provider);
      if (nextProvider === state.provider) return;

      saveCurrentProviderFormToState();
      state.provider = nextProvider;
      updateProviderButtons();
      fillProviderFormFromState();
      showToast(state.provider === 'google'
        ? 'Загружены сохранённые настройки Google API'
        : 'Загружены сохранённые настройки OpenAPI');
    });
  });
}

function bindProxySwitch() {
  const checkbox = document.getElementById('useProxy');
  const proxyFields = document.getElementById('proxyFields');
  const inputs = proxyFields.querySelectorAll('input');
  checkbox.addEventListener('change', () => {
    proxyFields.classList.toggle('disabled', !checkbox.checked);
    inputs.forEach(input => input.disabled = !checkbox.checked);
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
    const profile = currentProviderProfile();
    profile.apiKeys = cleanApiKeys([...profileApiKeys(profile), '']);
    renderApiKeyInputs(profile.apiKeys);
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
  document.getElementById('serializedPrompt').value = serializePrompt();
}


async function loadCurrentSettings() {
  try {
    const response = await fetch('/api/settings/current');
    if (!response.ok) return;
    const settings = await response.json();
    if (!settings || Object.keys(settings).length === 0) return;

    setServiceEnabled(settings.serviceEnabled !== false);

    if (settings.provider) {
      state.provider = settings.provider;
      document.querySelectorAll('.provider-button').forEach(button => {
        button.classList.toggle('active', button.dataset.provider === state.provider);
      });
    }

    if (settings.promptTemplate) {
      document.getElementById('promptEditor').innerHTML = promptTextToHtml(String(settings.promptTemplate));
      renderFields();
    }

    setLeadTriggerEvent(settings.leadTriggerEvent || 'ONCRMLEADADD');

    loadProviderProfilesFromSettings(settings);
    updateProviderButtons();
    fillProviderFormFromState();

    const proxy = settings.proxy || {};
    const useProxy = document.getElementById('useProxy');
    useProxy.checked = Boolean(proxy.enabled);
    useProxy.dispatchEvent(new Event('change'));
    setValue('proxyHost', proxy.host);
    setValue('proxyPort', proxy.port);
    setValue('proxyLogin', proxy.login);
    setValue('proxyPassword', proxy.password);

    updateSerializedPrompt();
  } catch (error) {
    console.warn('Settings load failed', error);
  }
}


function loadProviderProfilesFromSettings(settings) {
  const savedProfiles = settings.llmProfiles || settings.llmByProvider || {};

  ['openai', 'google'].forEach(provider => {
    const saved = savedProfiles[provider] || {};
    state.llmProfiles[provider] = {
      ...state.llmProfiles[provider],
      ...cleanProviderProfile(saved, provider)
    };
  });

  // Compatibility with older settings where only one `llm` block existed.
  const oldLlm = settings.llm || {};
  const oldProvider = normalizeProvider(oldLlm.provider || settings.provider || state.provider);
  if (oldLlm.endpointUrl || oldLlm.modelId || oldLlm.apiKey || oldLlm.apiKeys) {
    state.llmProfiles[oldProvider] = {
      ...state.llmProfiles[oldProvider],
      ...cleanProviderProfile(oldLlm, oldProvider)
    };
  }

  state.provider = normalizeProvider(settings.provider || oldLlm.provider || state.provider);
}

function cleanProviderProfile(profile, provider) {
  const defaults = defaultProviderProfile(provider);
  const keys = apiKeysFromSavedProfile(profile);
  return {
    provider,
    endpointUrl: nonBlank(profile.endpointUrl, defaults.endpointUrl),
    modelId: nonBlank(profile.modelId, defaults.modelId),
    apiKey: keys[0] || '',
    apiKeys: keys.length ? keys : ['']
  };
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

function currentProviderProfile() {
  const provider = normalizeProvider(state.provider);
  if (!state.llmProfiles[provider]) {
    state.llmProfiles[provider] = defaultProviderProfile(provider);
  }
  return state.llmProfiles[provider];
}

function saveCurrentProviderFormToState() {
  const provider = normalizeProvider(state.provider);
  const apiKeys = cleanApiKeys(readApiKeysFromForm());
  state.llmProfiles[provider] = {
    provider,
    endpointUrl: document.getElementById('endpointUrl')?.value || '',
    modelId: document.getElementById('modelId')?.value || '',
    apiKey: firstRealApiKey(apiKeys),
    apiKeys
  };
}

function fillProviderFormFromState() {
  const profile = currentProviderProfile();
  setInputValue('endpointUrl', profile.endpointUrl);
  setInputValue('modelId', profile.modelId);
  renderApiKeyInputs(profileApiKeys(profile));
}

function updateProviderButtons() {
  document.querySelectorAll('.provider-button').forEach(button => {
    button.classList.toggle('active', normalizeProvider(button.dataset.provider) === state.provider);
  });
}

function serializeLlmProfiles() {
  const result = {};
  ['openai', 'google'].forEach(provider => {
    const profile = state.llmProfiles[provider] || defaultProviderProfile(provider);
    result[provider] = {
      provider,
      endpointUrl: profile.endpointUrl || '',
      modelId: profile.modelId || '',
      apiKey: firstRealApiKey(profileApiKeys(profile)),
      apiKeys: cleanApiKeys(profileApiKeys(profile)).filter(Boolean),
      apiKeyPresent: Boolean(firstRealApiKey(profileApiKeys(profile))),
      apiKeyCount: cleanApiKeys(profileApiKeys(profile)).filter(Boolean).length
    };
  });
  return result;
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
    row.querySelector('.api-key-input').addEventListener('input', () => saveCurrentProviderFormToState());
    row.querySelector('.api-key-remove').addEventListener('click', () => {
      const current = readApiKeysFromForm();
      current.splice(index, 1);
      const profile = currentProviderProfile();
      profile.apiKeys = cleanApiKeys(current.length ? current : ['']);
      profile.apiKey = firstRealApiKey(profile.apiKeys);
      renderApiKeyInputs(profile.apiKeys);
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

function setValue(id, value) {
  if (value === undefined || value === null) return;
  const element = document.getElementById(id);
  if (element) element.value = value;
}

async function saveSettings(section) {
  updateSerializedPrompt();
  saveCurrentProviderFormToState();

  const currentProfile = currentProviderProfile();
  const currentApiKeys = cleanApiKeys(profileApiKeys(currentProfile)).filter(Boolean);
  const payload = {
    section,
    provider: state.provider,
    serviceEnabled: state.serviceEnabled,
    promptTemplate: serializePrompt(),
    llm: {
      provider: state.provider,
      endpointUrl: currentProfile.endpointUrl,
      modelId: currentProfile.modelId,
      apiKey: currentApiKeys[0] || '',
      apiKeys: currentApiKeys,
      apiKeyPresent: currentApiKeys.length > 0,
      apiKeyCount: currentApiKeys.length
    },
    llmProfiles: serializeLlmProfiles(),
    leadTriggerEvent: currentLeadTriggerEvent(),
    proxy: {
      enabled: document.getElementById('useProxy').checked,
      host: document.getElementById('proxyHost').value,
      port: document.getElementById('proxyPort').value,
      login: document.getElementById('proxyLogin').value,
      password: document.getElementById('proxyPassword').value,
      passwordPresent: Boolean(document.getElementById('proxyPassword').value)
    }
  };

  try {
    const response = await fetch('/api/settings/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await response.json();
    showToast(result.ok ? 'Настройки сохранены' : (result.message || 'Настройки сохранены'));
  } catch (error) {
    showToast('Ошибка сохранения: ' + error.message);
  }
}


async function applyWebhookMode() {
  await saveSettings('llm');
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
  const fields = [
    { id: 'ID', label: 'ID лида', group: 'Системное' },
    ...state.fields
  ];

  [first, second].forEach(select => {
    const current = select === first ? savedOne : savedTwo;
    select.innerHTML = fields.map(field =>
      `<option value="${escapeHtml(field.id)}">${escapeHtml(field.label || field.id)} · ${escapeHtml(field.id)}</option>`
    ).join('');
    select.value = fields.some(field => field.id === current) ? current : (select === first ? 'ID' : 'TITLE');
  });
  updateQueueColumnTitles();
}

function saveQueueColumnSelection() {
  const first = document.getElementById('queueFieldOne')?.value || 'ID';
  const second = document.getElementById('queueFieldTwo')?.value || 'TITLE';
  localStorage.setItem('leadprosvet.queueFieldOne', first);
  localStorage.setItem('leadprosvet.queueFieldTwo', second);
  updateQueueColumnTitles();
}

function updateQueueColumnTitles() {
  const one = document.getElementById('queueFieldOne')?.value || 'ID';
  const two = document.getElementById('queueFieldTwo')?.value || 'TITLE';
  const oneTitle = document.getElementById('queueFieldOneTitle');
  const twoTitle = document.getElementById('queueFieldTwoTitle');
  if (oneTitle) oneTitle.textContent = queueFieldLabel(one);
  if (twoTitle) twoTitle.textContent = queueFieldLabel(two);
}

function queueFieldLabel(id) {
  const field = [{ id: 'ID', label: 'ID лида' }, ...state.fields].find(item => item.id === id);
  return field ? `${field.label || field.id}` : id;
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
  updateQueueColumnTitles();
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
