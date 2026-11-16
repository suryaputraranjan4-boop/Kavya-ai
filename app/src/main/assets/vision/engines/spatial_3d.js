(function () {
  const V = window.KavyaVision;

  function makeHostCanvas(stage, id) {
    const c = document.createElement('canvas');
    c.id = id || 'vision-webgl-canvas';
    c.style.width = '100%';
    c.style.height = '100%';
    c.style.display = 'block';
    stage.appendChild(c);
    return c;
  }

  function uint8ToBase64(u8) {
    let bin = '';
    const chunk = 0x8000;
    for (let i = 0; i < u8.length; i += chunk) {
      bin += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    return btoa(bin);
  }

  function createThreeMeshFromSpec(THREE, item) {
    let geom;
    const r = Number(item.radius || 0.9);
    const t = String(item.type || 'icosahedron').toLowerCase();
    if (t === 'sphere') geom = new THREE.SphereGeometry(r, 28, 20);
    else if (t === 'box' || t === 'cube') geom = new THREE.BoxGeometry(r * 1.6, r * 1.6, r * 1.6);
    else if (t === 'cylinder') geom = new THREE.CylinderGeometry(r * 0.65, r * 0.65, r * 1.8, 24);
    else if (t === 'cone') geom = new THREE.ConeGeometry(r * 0.9, r * 1.8, 24);
    else if (t === 'torus') geom = new THREE.TorusGeometry(r, Number(item.tube || 0.08), 18, 56);
    else geom = new THREE.IcosahedronGeometry(r, Number(item.detail || 1));

    const mat = new THREE.MeshStandardMaterial({
      color: new THREE.Color(item.color || '#00F5FF'),
      metalness: item.metalness !== undefined ? Number(item.metalness) : 0.6,
      roughness: item.roughness !== undefined ? Number(item.roughness) : 0.25,
      emissive: new THREE.Color(item.emissive || '#000000')
    });
    const mesh = new THREE.Mesh(geom, mat);
    mesh.name = String(item.id || t);
    if (Array.isArray(item.position)) {
      mesh.position.set(Number(item.position[0] || 0), Number(item.position[1] || 0), Number(item.position[2] || 0));
    }
    if (Array.isArray(item.rotation)) {
      mesh.rotation.set(Number(item.rotation[0] || 0), Number(item.rotation[1] || 0), Number(item.rotation[2] || 0));
    }
    return mesh;
  }

  async function buildProceduralGlbBlobUrl() {
    await V.loadScriptOnce('/assets/vision/vendor/three.bundle.mjs');
    const THREE = window.THREE;
    const { GLTFExporter } = window.KavyaThreeAddons;
    const scene = new THREE.Scene();
    const core = new THREE.Mesh(
      new THREE.CapsuleGeometry(0.45, 0.8, 8, 20),
      new THREE.MeshStandardMaterial({ color: 0x00e5ff, metalness: 0.72, roughness: 0.22 })
    );
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(0.92, 0.07, 16, 48),
      new THREE.MeshStandardMaterial({ color: 0x10b981, metalness: 0.85, roughness: 0.18 })
    );
    ring.rotation.x = Math.PI / 2.6;
    scene.add(core);
    scene.add(ring);
    const exporter = new GLTFExporter();
    return new Promise((resolve, reject) => {
      exporter.parse(
        scene,
        (bin) => {
          const blob = new Blob([bin], { type: 'model/gltf-binary' });
          resolve({ url: URL.createObjectURL(blob), buffer: bin });
        },
        (err) => reject(err),
        { binary: true }
      );
    });
  }

  // 1. THREE.JS
  V.registerRenderer('three_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/three.bundle.mjs');
      V.emitReady('three_js', 'Three.js 0.187.0');
      const THREE = window.THREE;
      const { OrbitControls } = window.KavyaThreeAddons;

      const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
      renderer.setPixelRatio(V.getDpr());
      renderer.setSize(stage.clientWidth || 360, stage.clientHeight || 360);
      renderer.domElement.style.width = '100%';
      renderer.domElement.style.height = '100%';
      stage.appendChild(renderer.domElement);

      const scene = new THREE.Scene();
      scene.background = new THREE.Color(payload.background || '#070B12');
      const camera = new THREE.PerspectiveCamera(48, (stage.clientWidth || 1) / (stage.clientHeight || 1), 0.1, 200);
      camera.position.set(0, 2.2, 5.4);

      const controls = new OrbitControls(camera, renderer.domElement);
      controls.enableDamping = !V.isReducedMotion();
      controls.autoRotate = Boolean(payload.autoRotate) && !V.isReducedMotion();
      controls.autoRotateSpeed = 1.6;

      scene.add(new THREE.AmbientLight(0xffffff, 0.7));
      const key = new THREE.DirectionalLight(0x7dd3fc, 2.2);
      key.position.set(4, 6, 5);
      scene.add(key);
      const fill = new THREE.DirectionalLight(0x10b981, 1.1);
      fill.position.set(-5, -2, -3);
      scene.add(fill);

      const grid = new THREE.GridHelper(10, 12, 0x1e293b, 0x0f172a);
      grid.position.y = -2.2;
      scene.add(grid);

      const group = new THREE.Group();
      group.name = 'MainAssembly';
      const objs = Array.isArray(payload.objects) && payload.objects.length ? payload.objects : [{ id: 'core', type: 'icosahedron', color: '#00F5FF' }];
      let wireframe = Boolean(payload.wireframe);
      objs.forEach((o) => {
        const m = createThreeMeshFromSpec(THREE, o);
        m.material.wireframe = wireframe;
        group.add(m);
      });
      scene.add(group);

      let raf = 0;
      const animate = () => {
        raf = requestAnimationFrame(animate);
        controls.update();
        renderer.render(scene, camera);
      };
      animate();

      const onResize = () => {
        if (!stage.clientWidth || !stage.clientHeight) return;
        camera.aspect = stage.clientWidth / stage.clientHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(stage.clientWidth, stage.clientHeight);
      };
      window.addEventListener('resize', onResize);

      this._state = { THREE, scene, camera, controls, renderer, group };

      V.setHudButtons([
        {
          label: 'Wireframe',
          active: wireframe,
          onClick: (btn) => {
            wireframe = !wireframe;
            btn.classList.toggle('active', wireframe);
            group.traverse((n) => { if (n.isMesh && n.material) n.material.wireframe = wireframe; });
          }
        },
        {
          label: 'Auto-Orbit',
          active: controls.autoRotate,
          onClick: (btn) => {
            controls.autoRotate = !controls.autoRotate;
            btn.classList.toggle('active', controls.autoRotate);
          }
        },
        {
          label: 'Reset View',
          onClick: () => {
            camera.position.set(0, 2.2, 5.4);
            controls.target.set(0, 0, 0);
            controls.update();
          }
        }
      ]);

      V.emitComplete('three_js', { objectCount: objs.length });

      return () => {
        cancelAnimationFrame(raf);
        window.removeEventListener('resize', onResize);
        controls.dispose();
        scene.traverse((n) => {
          if (n.geometry) n.geometry.dispose();
          if (n.material) {
            if (Array.isArray(n.material)) n.material.forEach((m) => m.dispose());
            else n.material.dispose();
          }
        });
        renderer.dispose();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PNG') {
        V.canvasExportPng(this._state.renderer.domElement, 'three_js');
      } else if (fmt === 'GLB') {
        const { GLTFExporter } = window.KavyaThreeAddons;
        const exporter = new GLTFExporter();
        exporter.parse(
          this._state.group,
          (bin) => V.emitExport('three_js', 'GLB', uint8ToBase64(new Uint8Array(bin)), true),
          (err) => V.emitError('three_js', 'GLB_EXPORT_ERR', err?.message || 'GLB export error'),
          { binary: true }
        );
      }
    }
  });

  // 2. BABYLON.JS
  V.registerRenderer('babylon_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/babylon.bundle.mjs');
      V.emitReady('babylon_js', 'Babylon.js 9.36.0');
      const B = window.BABYLON;
      const canvas = makeHostCanvas(stage, 'babylon-canvas');
      const engine = new B.Engine(canvas, true, { preserveDrawingBuffer: true, stencil: true });
      const scene = new B.Scene(engine);
      scene.clearColor = new B.Color4(0.027, 0.043, 0.07, 1.0);

      const camera = new B.ArcRotateCamera('cam', Math.PI / 3, Math.PI / 3, 10, B.Vector3.Zero(), scene);
      camera.attachControl(canvas, true);
      new B.HemisphericLight('hemi', new B.Vector3(0, 1, 0), scene).intensity = 0.85;
      const dir = new B.DirectionalLight('dir', new B.Vector3(-1, -2, -1), scene);
      dir.position = new B.Vector3(6, 10, 6);
      dir.intensity = 1.4;

      const ground = B.MeshBuilder.CreateGround('ground', { width: Number(payload.groundSize || 10), height: Number(payload.groundSize || 10) }, scene);
      const gMat = new B.StandardMaterial('gMat', scene);
      gMat.diffuseColor = B.Color3.FromHexString('#0F172A');
      gMat.specularColor = new B.Color3(0.1, 0.2, 0.25);
      ground.material = gMat;

      const specs = Array.isArray(payload.bodies) && payload.bodies.length ? payload.bodies : [
        { name: 'Orb1', shape: 'sphere', diameter: 1.2, position: [0, 3.8, 0], restitution: 0.82, color: '#00E5FF' }
      ];
      let gravityOn = payload.gravityEnabled !== false && !V.isReducedMotion();
      const bodyStates = specs.map((b, idx) => {
        const d = Number(b.diameter || 1.0);
        const mesh = String(b.shape || 'sphere') === 'box'
          ? B.MeshBuilder.CreateBox(b.name || 'Box' + idx, { size: d }, scene)
          : B.MeshBuilder.CreateSphere(b.name || 'Sphere' + idx, { diameter: d, segments: 24 }, scene);
        const pos = Array.isArray(b.position) ? b.position : [idx * 1.4 - 1, 3 + idx, 0];
        mesh.position = new B.Vector3(Number(pos[0] || 0), Number(pos[1] || 3), Number(pos[2] || 0));
        const pbr = new B.PBRMetallicRoughnessMaterial('pbr_' + idx, scene);
        pbr.baseColor = B.Color3.FromHexString(b.color || '#00E5FF');
        pbr.metallic = 0.7;
        pbr.roughness = 0.22;
        mesh.material = pbr;
        return {
          mesh,
          startY: mesh.position.y,
          vy: 0,
          radius: d * 0.5,
          restitution: Number(b.restitution || 0.8)
        };
      });

      scene.registerBeforeRender(() => {
        if (!gravityOn) return;
        const dt = Math.min(engine.getDeltaTime() / 1000, 0.04);
        bodyStates.forEach((bs) => {
          bs.vy -= 9.81 * dt;
          bs.mesh.position.y += bs.vy * dt;
          bs.mesh.rotation.y += 0.6 * dt;
          if (bs.mesh.position.y <= bs.radius) {
            bs.mesh.position.y = bs.radius;
            bs.vy = Math.abs(bs.vy) * bs.restitution;
            if (bs.vy < 0.25) bs.vy = 0;
          }
        });
      });

      engine.runRenderLoop(() => scene.render());
      const onResize = () => engine.resize();
      window.addEventListener('resize', onResize);

      V.setHudButtons([
        {
          label: 'Gravity',
          active: gravityOn,
          onClick: (btn) => {
            gravityOn = !gravityOn;
            btn.classList.toggle('active', gravityOn);
          }
        },
        {
          label: 'Drop Reset',
          onClick: () => {
            bodyStates.forEach((bs) => {
              bs.mesh.position.y = Math.max(bs.startY, 3.5);
              bs.vy = 0;
            });
            gravityOn = true;
          }
        }
      ]);

      this._state = { engine, scene, canvas };
      V.emitComplete('babylon_js', { bodies: bodyStates.length });

      return () => {
        window.removeEventListener('resize', onResize);
        engine.stopRenderLoop();
        scene.dispose();
        engine.dispose();
        this._state = null;
      };
    },
    async exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PNG') {
        V.canvasExportPng(this._state.canvas, 'babylon_js');
      } else if (fmt === 'GLB') {
        const exp = window.KavyaBabylonSerializers?.GLTF2Export;
        if (!exp) { V.emitError('babylon_js', 'NO_EXPORTER', 'Babylon GLTF2Export unavailable'); return; }
        const glb = await exp.GLBAsync(this._state.scene, 'babylon_scene');
        const blob = glb.glTFFiles['babylon_scene.glb'];
        const buf = await blob.arrayBuffer();
        V.emitExport('babylon_js', 'GLB', uint8ToBase64(new Uint8Array(buf)), true);
      }
    }
  });

  // 3. MODEL VIEWER
  V.registerRenderer('model_viewer', {
    _state: null,
    async mount(stage, payload, options) {
      await V.loadScriptOnce('/assets/vision/vendor/model-viewer.bundle.mjs');
      V.emitReady('model_viewer', 'model-viewer 4.4.0');
      let objectUrlToRevoke = null;
      let latestGlbBuffer = null;
      let src = payload.modelSource || '';
      if (!src || src.startsWith('procedural://')) {
        const built = await buildProceduralGlbBlobUrl();
        src = built.url;
        objectUrlToRevoke = built.url;
        latestGlbBuffer = built.buffer;
      }

      const mv = document.createElement('model-viewer');
      mv.src = src;
      mv.setAttribute('camera-controls', '');
      mv.setAttribute('shadow-intensity', String(payload.shadowIntensity ?? 0.9));
      mv.setAttribute('exposure', String(payload.exposure ?? 1.1));
      if (payload.autoRotate !== false && !V.isReducedMotion()) mv.setAttribute('auto-rotate', '');
      if (options?.arSupported) mv.setAttribute('ar', '');
      mv.style.width = '100%';
      mv.style.height = '100%';
      mv.style.background = '#070B12';

      (payload.hotspots || []).forEach((h) => {
        const btn = document.createElement('button');
        btn.slot = h.slot || 'hotspot-1';
        btn.setAttribute('data-position', h.position || '0 0.3 0.3');
        btn.setAttribute('data-normal', h.normal || '0 0 1');
        btn.textContent = h.label || 'Point';
        btn.style.cssText = 'background:#0EA5E9;color:#04101B;border:none;border-radius:999px;padding:3px 8px;font-size:10px;font-weight:700;';
        mv.appendChild(btn);
      });
      stage.appendChild(mv);

      V.setHudButtons([
        { label: 'Front', onClick: () => { mv.cameraOrbit = '0deg 75deg 105%'; } },
        { label: 'Top', onClick: () => { mv.cameraOrbit = '0deg 10deg 105%'; } },
        { label: 'Hero', onClick: () => { mv.cameraOrbit = '42deg 65deg 105%'; } }
      ]);

      this._state = { mv, getBuffer: () => latestGlbBuffer };
      mv.addEventListener('load', () => V.emitComplete('model_viewer', { source: payload.modelSource || 'procedural' }));
      mv.addEventListener('error', (ev) => V.emitError('model_viewer', 'MV_LOAD_ERR', ev?.detail?.type || 'Model failed to load'));
      if (!src.startsWith('http')) V.emitComplete('model_viewer', { source: src.slice(0, 24) });

      return () => {
        if (objectUrlToRevoke) URL.revokeObjectURL(objectUrlToRevoke);
        this._state = null;
      };
    },
    async exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PNG') {
        const dataUrl = this._state.mv.toDataURL('image/png');
        V.emitExport('model_viewer', 'PNG', dataUrl, true);
      } else if (fmt === 'GLB') {
        const buf = this._state.getBuffer();
        if (buf) V.emitExport('model_viewer', 'GLB', uint8ToBase64(new Uint8Array(buf)), true);
        else if (typeof this._state.mv.exportScene === 'function') {
          const blob = await this._state.mv.exportScene({ binary: true });
          const ab = await blob.arrayBuffer();
          V.emitExport('model_viewer', 'GLB', uint8ToBase64(new Uint8Array(ab)), true);
        } else {
          V.emitError('model_viewer', 'GLB_UNAVAILABLE', 'Export requires loaded procedural or editable mesh.');
        }
      }
    }
  });

  // 4. THREEPIPE STUDIO VIEWER
  V.registerRenderer('threepipe', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/three.bundle.mjs');
      await V.loadScriptOnce('/assets/vision/vendor/threepipe.bundle.mjs');
      V.emitReady('threepipe', 'Threepipe 0.6.0');
      const THREE = window.THREE;
      const { OrbitControls } = window.KavyaThreeAddons;
      const canvas = makeHostCanvas(stage, 'threepipe-canvas');
      const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
      renderer.setPixelRatio(V.getDpr());
      renderer.setSize(stage.clientWidth || 360, stage.clientHeight || 360);
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = Number(payload.envIntensity ?? 1.2);

      const scene = new THREE.Scene();
      scene.background = new THREE.Color('#090D16');
      const camera = new THREE.PerspectiveCamera(45, (stage.clientWidth || 1) / (stage.clientHeight || 1), 0.1, 100);
      camera.position.set(0, 1.5, 4.2);
      const controls = new OrbitControls(camera, canvas);
      controls.enableDamping = true;

      scene.add(new THREE.AmbientLight(0xffffff, 0.6));
      const key = new THREE.DirectionalLight(0xffffff, 2.5);
      key.position.set(3, 5, 4);
      scene.add(key);
      const rim = new THREE.PointLight(0x00e5ff, 3.0, 12);
      rim.position.set(-3, 2, -2);
      scene.add(rim);

      const geom = payload.meshPreset === 'sphere'
        ? new THREE.SphereGeometry(1.1, 42, 32)
        : new THREE.TorusKnotGeometry(0.85, 0.28, 128, 24);
      const mat = new THREE.MeshPhysicalMaterial({
        color: new THREE.Color(payload.color || '#00E5FF'),
        metalness: Number(payload.metalness ?? 0.85),
        roughness: Number(payload.roughness ?? 0.22),
        clearcoat: Number(payload.clearcoat ?? 0.6),
        clearcoatRoughness: 0.1
      });
      const mesh = new THREE.Mesh(geom, mat);
      scene.add(mesh);

      let raf = 0;
      const tick = () => {
        raf = requestAnimationFrame(tick);
        if (!V.isReducedMotion()) mesh.rotation.y += 0.005;
        controls.update();
        renderer.render(scene, camera);
      };
      tick();

      const palette = ['#00E5FF', '#10B981', '#F59E0B', '#E2E8F0'];
      let colIdx = 0;
      V.setHudButtons([
        {
          label: 'Cycle Albedo',
          onClick: () => {
            colIdx = (colIdx + 1) % palette.length;
            mat.color.set(palette[colIdx]);
            V.emitMutated('threepipe', { ...payload, color: palette[colIdx] }, 'Albedo ' + palette[colIdx]);
          }
        },
        {
          label: 'Matte / Chrome',
          onClick: () => {
            mat.roughness = mat.roughness > 0.4 ? 0.15 : 0.65;
            V.emitMutated('threepipe', { ...payload, roughness: mat.roughness }, 'Roughness ' + mat.roughness);
          }
        }
      ]);

      this._state = { canvas, mesh, renderer, scene };
      V.emitComplete('threepipe', { runtime: 'Threepipe-Studio' });

      return () => {
        cancelAnimationFrame(raf);
        controls.dispose();
        geom.dispose();
        mat.dispose();
        renderer.dispose();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PNG') V.canvasExportPng(this._state.canvas, 'threepipe');
      else if (fmt === 'GLB') {
        const { GLTFExporter } = window.KavyaThreeAddons;
        new GLTFExporter().parse(
          this._state.scene,
          (bin) => V.emitExport('threepipe', 'GLB', uint8ToBase64(new Uint8Array(bin)), true),
          (e) => V.emitError('threepipe', 'GLB_ERR', String(e)),
          { binary: true }
        );
      }
    }
  });

  // 5. GSPLAT.JS
  V.registerRenderer('gsplat_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/gsplat.bundle.mjs');
      V.emitReady('gsplat_js', 'gsplat.js 1.2.9');
      V.setAttribution(payload.attribution || 'Local Gaussian Splat view');

      const SPLAT = window.KavyaGsplat;
      const canvas = makeHostCanvas(stage, 'gsplat-canvas');
      const gRenderer = new SPLAT.WebGLRenderer(canvas);
      const scene = new SPLAT.Scene();
      const camera = new SPLAT.Camera();
      const controls = new SPLAT.OrbitControls(camera, canvas);

      if (payload.sourceMode === 'local_file' && payload.modelSource) {
        if (String(payload.modelSource).toLowerCase().endsWith('.ply')) {
          await SPLAT.PLYLoader.LoadAsync(payload.modelSource, scene);
        } else {
          await SPLAT.Loader.LoadAsync(payload.modelSource, scene);
        }
      } else {
        // Generate an authentic procedural Gaussian Splat point distribution bounded by device quality preset
        const cap = V.getQuality() === 'low' ? 1800 : (V.getQuality() === 'high' ? 6000 : 3600);
        const count = Math.min(Number(payload.splatCount || 4000), cap);
        const positions = new Float32Array(count * 3);
        const rotations = new Float32Array(count * 4);
        const scales = new Float32Array(count * 3);
        const colors = new Uint8Array(count * 4);
        for (let i = 0; i < count; i++) {
          const t = (i / count) * Math.PI * 10;
          const r = 0.5 + 1.3 * (i / count);
          const arm = (i % 2) * Math.PI;
          positions[i * 3] = Math.cos(t + arm) * r;
          positions[i * 3 + 1] = ((i / count) - 0.5) * 2.2;
          positions[i * 3 + 2] = Math.sin(t + arm) * r;
          rotations[i * 4] = 1;
          scales[i * 3] = scales[i * 3 + 1] = scales[i * 3 + 2] = 0.035;
          colors[i * 4] = i % 2 === 0 ? 14 : 16;
          colors[i * 4 + 1] = i % 2 === 0 ? 220 : 185;
          colors[i * 4 + 2] = i % 2 === 0 ? 255 : 129;
          colors[i * 4 + 3] = 240;
        }
        const splatData = new SPLAT.SplatData(count, positions, rotations, scales, colors);
        scene.addObject(new SPLAT.Splat(splatData));
      }

      const resize = () => gRenderer.setSize(stage.clientWidth || 360, stage.clientHeight || 360);
      resize();
      window.addEventListener('resize', resize);

      let raf = 0;
      const frame = () => {
        controls.update();
        gRenderer.render(scene, camera);
        raf = requestAnimationFrame(frame);
      };
      frame();

      this._state = { canvas };
      V.emitComplete('gsplat_js', { quality: V.getQuality() });

      return () => {
        cancelAnimationFrame(raf);
        window.removeEventListener('resize', resize);
        controls.dispose();
        gRenderer.dispose();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (fmt === 'PNG' && this._state) V.canvasExportPng(this._state.canvas, 'gsplat_js');
    }
  });

  // 6. CESIUM.JS
  V.registerRenderer('cesium_js', {
    _state: null,
    async mount(stage, payload, options) {
      window.CESIUM_BASE_URL = '/assets/vision/vendor/cesium/';
      V.loadCssOnce('/assets/vision/vendor/cesium/Widgets/widgets.css');
      await V.loadScriptOnce('/assets/vision/vendor/cesium/Cesium.js');
      V.emitReady('cesium_js', 'CesiumJS 1.147.0');
      const Cesium = window.Cesium;

      const userToken = String(options?.cesiumIonToken || '').trim();
      if (userToken) {
        Cesium.Ion.defaultAccessToken = userToken;
      }

      const container = document.createElement('div');
      container.style.width = '100%';
      container.style.height = '100%';
      stage.appendChild(container);

      let imageryProvider = null;
      if (!userToken) {
        try {
          imageryProvider = await Cesium.TileMapServiceImageryProvider.fromUrl(
            Cesium.buildModuleUrl('Assets/Textures/NaturalEarthII')
          );
        } catch (_) {}
      }

      const viewer = new Cesium.Viewer(container, {
        animation: false,
        timeline: false,
        baseLayerPicker: false,
        geocoder: false,
        homeButton: true,
        sceneModePicker: false,
        navigationHelpButton: false,
        fullscreenButton: false,
        ...(imageryProvider ? { baseLayer: new Cesium.ImageryLayer(imageryProvider) } : {})
      });
      if (viewer.creditDisplay?.container) viewer.creditDisplay.container.style.fontSize = '9px';

      const markers = Array.isArray(payload.markers) ? payload.markers : [];
      const byName = {};
      markers.forEach((m) => {
        const lat = Number(m.lat || 0);
        const lon = Number(m.lon || 0);
        byName[m.name] = [lon, lat];
        viewer.entities.add({
          name: m.name || 'Marker',
          position: Cesium.Cartesian3.fromDegrees(lon, lat, 12000),
          point: { pixelSize: 10, color: Cesium.Color.fromCssColorString(m.color || '#00F5FF') },
          label: { text: m.name || '', font: '12px sans-serif', pixelOffset: new Cesium.Cartesian2(0, -14), fillColor: Cesium.Color.WHITE }
        });
      });

      (payload.routes || []).forEach((r) => {
        const p1 = byName[r.from];
        const p2 = byName[r.to];
        if (p1 && p2) {
          viewer.entities.add({
            polyline: {
              positions: Cesium.Cartesian3.fromDegreesArray([p1[0], p1[1], p2[0], p2[1]]),
              width: 3,
              material: Cesium.Color.fromCssColorString('#38BDF8')
            }
          });
        }
      });

      if (markers.length) {
        const first = markers[0];
        viewer.camera.flyTo({ destination: Cesium.Cartesian3.fromDegrees(Number(first.lon), Number(first.lat), 8500000), duration: 1.0 });
      }

      V.setHudButtons(markers.slice(0, 3).map((m) => ({
        label: 'Fly: ' + m.name,
        onClick: () => viewer.camera.flyTo({ destination: Cesium.Cartesian3.fromDegrees(Number(m.lon), Number(m.lat), 3500000), duration: 1.0 })
      })));

      this._state = { viewer };
      V.emitComplete('cesium_js', { markers: markers.length, mode: userToken ? 'Ion' : 'Offline NaturalEarthII' });

      return () => {
        viewer.destroy();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (fmt === 'PNG' && this._state) {
        this._state.viewer.render();
        V.canvasExportPng(this._state.viewer.scene.canvas, 'cesium_js');
      }
    }
  });
})();
