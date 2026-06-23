const state = {
  fields: [],
  provider: 'openai',
  drag: null
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
  bindProviderSwitch();
  bindProxySwitch();
  bindPromptEditor();
  bindButtons();
  loadFields().then(loadCurrentSettings);
  updateSerializedPrompt();
});

function bindTabs() {
  document.querySelectorAll('.tab-button').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('.tab-button').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      button.classList.add('active');
      document.getElementById(`tab-${button.dataset.tab}`).classList.add('active');
    });
  });
}

function bindProviderSwitch() {
  document.querySelectorAll('.provider-button').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('.provider-button').forEach(b => b.classList.remove('active'));
      button.classList.add('active');
      state.provider = button.dataset.provider;
      const endpoint = document.getElementById('endpointUrl');
      const model = document.getElementById('modelId');
      if (state.provider === 'google') {
        endpoint.value = 'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent';
        model.value = 'gemini-2.5-flash';
      } else {
        endpoint.value = 'https://api.openai.com/v1/chat/completions';
        model.value = 'gpt-4.1-mini';
      }
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

  document.addEventListener('pointermove', onPointerMove);
  document.addEventListener('pointerup', onPointerUp);
  document.addEventListener('pointercancel', cancelDrag);
}

async function loadFields() {
  try {
    const response = await fetch('/api/settings/contact-fields');
    state.fields = await response.json();
    renderFields();
  } catch (error) {
    showToast('Не удалось загрузить моковые поля: ' + error.message);
  }
}

function renderFields() {
  const query = document.getElementById('fieldSearch').value.trim().toLowerCase();
  const list = document.getElementById('fieldList');
  const used = usedFieldIds();
  list.innerHTML = '';

  state.fields
    .filter(field => !used.has(field.id))
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
  if (usedFieldIds().has(field.id)) {
    showToast(`Поле «${field.label}» уже добавлено в промпт`);
    return;
  }

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
  return clone.innerText
    .replace(/\n{3,}/g, '\n\n')
    .trim();
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

    const llm = settings.llm || {};
    setValue('endpointUrl', llm.endpointUrl);
    setValue('modelId', llm.modelId);
    setValue('apiKey', llm.apiKey);

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

function promptTextToHtml(text) {
  const escaped = escapeHtml(text);
  const withTokens = escaped.replace(/\{\{([A-Z0-9_]+)}}/g, (_, fieldId) => {
    const field = state.fields.find(item => item.id === fieldId) || { id: fieldId, label: fieldId };
    return tokenHtml(field);
  });
  return withTokens
    .split(/\n{2,}/)
    .map(part => `<p>${part.replace(/\n/g, '<br>')}</p>`)
    .join('');
}

function setValue(id, value) {
  if (value === undefined || value === null) return;
  const element = document.getElementById(id);
  if (element) element.value = value;
}

async function saveSettings(section) {
  updateSerializedPrompt();
  const payload = {
    section,
    provider: state.provider,
    promptTemplate: serializePrompt(),
    llm: {
      provider: state.provider,
      endpointUrl: document.getElementById('endpointUrl').value,
      modelId: document.getElementById('modelId').value,
      apiKey: document.getElementById('apiKey').value,
      apiKeyPresent: Boolean(document.getElementById('apiKey').value)
    },
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
