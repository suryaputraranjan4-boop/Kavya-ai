(function () {
  const loadedScripts = new Set();
  const loadedCss = new Set();
  const renderers = new Map();

  let activeTech = null;
  let activePayload = null;
  let activeCleanup = null;
  let activeQuality = 'medium';
  let activeReducedMotion = false;

  const bridge = window.VisionJsBridge || null;

  function emitReady(techId, info) {
    try { bridge?.onEngineReady(techId, String(info || 'ready')); } catch (_) {}
  }

  function emitComplete(techId, metrics) {
    try { bridge?.onRenderComplete(techId, JSON.stringify(metrics || {})); } catch (_) {}
  }

  function emitError(techId, code, msg) {
    showStatusBanner('Render Error (' + techId + '): ' + msg);
    try { bridge?.onError(techId, String(code || 'RENDER_ERROR'), String(msg || 'Unknown error')); } catch (_) {}
  }

  function emitMutated(techId, payloadObj, label) {
    activePayload = payloadObj;
    try {
      bridge?.onStateMutated(techId, JSON.stringify(payloadObj), String(label || 'Edited'));
    } catch (_) {}
  }

  function emitExport(techId, format, data, isBase64) {
    try {
      bridge?.onExportResult(techId, String(format), String(data), Boolean(isBase64));
    } catch (_) {}
  }

  function showStatusBanner(text) {
    const el = document.getElementById('statusBanner');
    if (!el) return;
    if (!text) {
      el.style.display = 'none';
      el.textContent = '';
    } else {
      el.style.display = 'flex';
      el.textContent = text;
    }
  }

  function setAttribution(text) {
    const el = document.getElementById('attribution');
    if (!el) return;
    if (!text) {
      el.style.display = 'none';
      el.textContent = '';
    } else {
      el.style.display = 'block';
      el.textContent = text;
    }
  }

  function setHudButtons(buttons) {
    const hud = document.getElementById('hud');
    if (!hud) return;
    hud.innerHTML = '';
    (buttons || []).forEach(btn => {
      const el = document.createElement('button');
      el.className = 'pill' + (btn.active ? ' active' : '');
      el.textContent = btn.label;
      el.onclick = () => {
        try { btn.onClick && btn.onClick(el); } catch (e) { console.error(e); }
      };
      hud.appendChild(el);
    });
  }

  function loadScriptOnce(url, isModule) {
    if (loadedScripts.has(url)) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = url;
      if (isModule) s.type = 'module';
      s.onload = () => { loadedScripts.add(url); resolve(); };
      s.onerror = () => reject(new Error('Failed loading script: ' + url));
      document.head.appendChild(s);
    });
  }

  function loadCssOnce(url) {
    if (loadedCss.has(url)) return;
    const l = document.createElement('link');
    l.rel = 'stylesheet';
    l.href = url;
    document.head.appendChild(l);
    loadedCss.add(url);
  }

  function destroyActive() {
    showStatusBanner('');
    setAttribution('');
    setHudButtons([]);
    if (typeof activeCleanup === 'function') {
      try { activeCleanup(); } catch (e) { console.warn('Cleanup error:', e); }
    }
    activeCleanup = null;
    const stage = document.getElementById('stage');
    if (stage) stage.innerHTML = '';
  }

  function getDpr() {
    const native = window.devicePixelRatio || 1;
    if (activeQuality === 'low') return Math.min(native, 1.0);
    if (activeQuality === 'high') return Math.min(native, 2.0);
    return Math.min(native, 1.5);
  }

  function canvasExportPng(canvasEl, techId) {
    if (!canvasEl || typeof canvasEl.toDataURL !== 'function') return false;
    try {
      const dataUrl = canvasEl.toDataURL('image/png');
      emitExport(techId, 'PNG', dataUrl, true);
      return true;
    } catch (e) {
      emitError(techId, 'EXPORT_PNG_FAILED', e.message || String(e));
      return false;
    }
  }

  function svgExport(svgEl, techId, format) {
    if (!svgEl) return false;
    const serializer = new XMLSerializer();
    let svgText = serializer.serializeToString(svgEl);
    if (!svgText.includes('xmlns=')) {
      svgText = svgText.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
    }
    if (format === 'SVG') {
      emitExport(techId, 'SVG', svgText, false);
      return true;
    }
    const img = new Image();
    const svgBlob = new Blob([svgText], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(svgBlob);
    img.onload = () => {
      const c = document.createElement('canvas');
      const rect = svgEl.getBoundingClientRect();
      c.width = Math.max(800, Math.round((rect.width || 800) * 1.5));
      c.height = Math.max(500, Math.round((rect.height || 500) * 1.5));
      const ctx = c.getContext('2d');
      ctx.fillStyle = '#070B12';
      ctx.fillRect(0, 0, c.width, c.height);
      ctx.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      emitExport(techId, 'PNG', c.toDataURL('image/png'), true);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      emitError(techId, 'SVG_PNG_FAILED', 'Could not rasterize SVG');
    };
    img.src = url;
    return true;
  }

  function registerRenderer(techId, handler) {
    renderers.set(techId, handler);
  }

  window.KavyaVision = {
    registerRenderer,
    loadScriptOnce,
    loadCssOnce,
    emitReady,
    emitComplete,
    emitError,
    emitMutated,
    emitExport,
    showStatusBanner,
    setAttribution,
    setHudButtons,
    getDpr,
    canvasExportPng,
    svgExport,
    isReducedMotion: () => activeReducedMotion,
    getQuality: () => activeQuality,
    setOptions: (opts) => {
      if (opts && opts.quality) activeQuality = opts.quality;
      if (opts && typeof opts.reducedMotion === 'boolean') activeReducedMotion = opts.reducedMotion;
    },
    render: async (techId, payloadObj, options) => {
      try {
        window.KavyaVision.setOptions(options || {});
        destroyActive();
        activeTech = techId;
        activePayload = payloadObj || {};
        const handler = renderers.get(techId);
        if (!handler) {
          emitError(techId, 'NO_RENDERER', 'No renderer registered for engine ' + techId);
          return;
        }
        const stage = document.getElementById('stage');
        const cleanupFn = await handler.mount(stage, activePayload, options || {});
        activeCleanup = typeof cleanupFn === 'function' ? cleanupFn : null;
      } catch (e) {
        emitError(techId, 'MOUNT_FAILED', e.message || String(e));
      }
    },
    requestExport: async (format) => {
      if (!activeTech) return;
      if (format === 'JSON') {
        emitExport(activeTech, 'JSON', JSON.stringify(activePayload || {}, null, 2), false);
        return;
      }
      const handler = renderers.get(activeTech);
      if (handler && typeof handler.exportFormat === 'function') {
        try {
          await handler.exportFormat(format, activePayload);
          return;
        } catch (e) {
          emitError(activeTech, 'EXPORT_ERROR', e.message || String(e));
          return;
        }
      }
      const canvas = document.querySelector('#stage canvas');
      if (format === 'PNG' && canvas) {
        canvasExportPng(canvas, activeTech);
        return;
      }
      const svg = document.querySelector('#stage svg');
      if ((format === 'PNG' || format === 'SVG') && svg) {
        svgExport(svg, activeTech, format);
        return;
      }
      emitError(activeTech, 'EXPORT_UNSUPPORTED', 'Export format ' + format + ' unavailable for ' + activeTech);
    },
    controlAction: (actionName, arg) => {
      const handler = renderers.get(activeTech);
      if (handler && typeof handler.control === 'function') {
        handler.control(actionName, arg);
      }
    },
    disposeAll: () => {
      destroyActive();
      activeTech = null;
    }
  };
})();
