const state = {
  fields: [],
  provider: 'openai'
};

const defaultPromptHtml = `
<p>Собери краткую справку о компании для первого звонка менеджера.</p>
<p>Данные из Bitrix24:</p>
<p>Компания: <span class="prompt-token" draggable="true" contenteditable="false" data-field-id="COMPANY_TITLE" data-field-label="Компания">Компания</span></p>
<p>Сайт: <span class="prompt-token" draggable="true" contenteditable="false" data-field-id="WEB" data-field-label="Сайт">Сайт</span></p>
<p>ИНН: <span class="prompt-token" draggable="true" contenteditable="false" data-field-id="UF_CRM_INN" data-field-label="ИНН">ИНН</span></p>
<p>Нужно найти и структурировать: чем занимается компания, ключевые продукты/услуги, размер/география если доступно, что важно знать перед первым звонком, возможные боли и первый заход для разговора.</p>`;

document.addEventListener('DOMContentLoaded', () => {
  bindTabs();
  bindProviderSwitch();
  bindProxySwitch();
  bindPromptEditor();
  bindButtons();
  loadFields();
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
  editor.addEventListener('dragover', event => {
    event.preventDefault();
    editor.classList.add('drag-over');
  });
  editor.addEventListener('dragleave', () => editor.classList.remove('drag-over'));
  editor.addEventListener('drop', event => {
    event.preventDefault();
    editor.classList.remove('drag-over');
    const raw = event.dataTransfer.getData('application/json') || event.dataTransfer.getData('text/plain');
    const field = parseDraggedField(raw);
    if (!field) return;
    placeCaretFromPoint(event.clientX, event.clientY);
    insertPromptToken(field);
    updateSerializedPrompt();
  });
  editor.addEventListener('input', updateSerializedPrompt);
  editor.addEventListener('keyup', updateSerializedPrompt);
  editor.addEventListener('mouseup', updateSerializedPrompt);
  editor.addEventListener('dragstart', event => {
    const token = event.target.closest('.prompt-token');
    if (!token) return;
    event.dataTransfer.setData('application/json', JSON.stringify({
      id: token.dataset.fieldId,
      label: token.dataset.fieldLabel
    }));
  });
}

function bindButtons() {
  document.getElementById('reloadFieldsBtn').addEventListener('click', loadFields);
  document.getElementById('fieldSearch').addEventListener('input', renderFields);
  document.getElementById('resetPromptBtn').addEventListener('click', () => {
    document.getElementById('promptEditor').innerHTML = defaultPromptHtml;
    updateSerializedPrompt();
  });
  document.getElementById('saveIntegrationBtn').addEventListener('click', () => saveSettings('integration'));
  document.getElementById('saveLlmBtn').addEventListener('click', () => saveSettings('llm'));
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
  list.innerHTML = '';
  state.fields
    .filter(field => !query || `${field.id} ${field.label} ${field.group}`.toLowerCase().includes(query))
    .forEach(field => {
      const chip = document.createElement('div');
      chip.className = 'field-chip';
      chip.draggable = true;
      chip.dataset.fieldId = field.id;
      chip.dataset.fieldLabel = field.label;
      chip.innerHTML = `<span>${escapeHtml(field.label)}</span><small>${escapeHtml(field.id)}</small>`;
      chip.addEventListener('dragstart', event => {
        event.dataTransfer.setData('application/json', JSON.stringify(field));
        event.dataTransfer.effectAllowed = 'copy';
      });
      chip.addEventListener('click', () => {
        document.getElementById('promptEditor').focus();
        insertPromptToken(field);
        updateSerializedPrompt();
      });
      list.appendChild(chip);
    });
}

function parseDraggedField(raw) {
  if (!raw) return null;
  try {
    const field = JSON.parse(raw);
    return field.id && field.label ? field : null;
  } catch {
    return null;
  }
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
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}

function insertPromptToken(field) {
  const selection = window.getSelection();
  if (!selection.rangeCount) {
    document.getElementById('promptEditor').focus();
  }
  const range = selection.rangeCount ? selection.getRangeAt(0) : document.createRange();
  const token = document.createElement('span');
  token.className = 'prompt-token';
  token.draggable = true;
  token.contentEditable = 'false';
  token.dataset.fieldId = field.id;
  token.dataset.fieldLabel = field.label;
  token.textContent = field.label;

  const space = document.createTextNode(' ');
  range.deleteContents();
  range.insertNode(space);
  range.insertNode(token);
  range.setStartAfter(space);
  range.setEndAfter(space);
  selection.removeAllRanges();
  selection.addRange(range);
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
      apiKeyPresent: Boolean(document.getElementById('apiKey').value),
      temperature: document.getElementById('temperature').value,
      maxTokens: document.getElementById('maxTokens').value
    },
    proxy: {
      enabled: document.getElementById('useProxy').checked,
      host: document.getElementById('proxyHost').value,
      port: document.getElementById('proxyPort').value,
      login: document.getElementById('proxyLogin').value,
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
    showToast(result.message || 'Настройки сохранены');
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
