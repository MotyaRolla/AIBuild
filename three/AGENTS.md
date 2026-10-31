# Задание агентам: AIBuild 3D (three.js)

Общая идея — в `three/IDEA.md`, контракт данных — `three/packages/core/src/spec.mjs` и `materials.mjs`.
Всё пишем на **чистом ESM JavaScript** (без TypeScript, без сборщика), node 24, тесты — `node --test`.
three.js установлен в `three/node_modules/three` (версия 0.187.0) и используется ТОЛЬКО в браузере (студия).
Диск ограничен (~1 ГБ): не устанавливайте новые зависимости, всё делаем на стандартной библиотеке node.
Как рендерить картинки в node без GPU — см. следующий раздел.

## Критично: как рендерить в node

В окружении нет headless-браузера и нет GPU. Поэтому **рендер не через WebGL**. Агент, отвечающий за рендер,
пишет **софтверный растеризатор на JS**: собирает треугольники мешей (three.js `BufferGeometry` можно взять как
источник данных, либо строить треугольники напрямую), сортирует по глубине и рисует в собственный буфер RGBA,
затем пишет PNG (свой минимальный PNG-энкодер с zlib из `node:zlib` — inflate/deflate доступны).
Это должно быть быстро: сцена ≤ 200k треугольников, 800×600, 4 ракурса ≤ 20 с суммарно.

## Общие правила

* Пакеты: `packages/core` (данные, геометрия, рендер, перенос), `packages/studio` (веб), `packages/transfer`.
* Никаких правок файлов других агентов, кроме точек стыка, указанных в задании.
* Экспорт: только `export` из файлов, названия файлов — как в задании.
* Тесты: `packages/<pkg>/test/<name>.test.mjs` на `node:test` + `node:assert/strict`; запуск
  `cd three && node --test packages/<pkg>/test/`.
* Комментарии и JSDoc — по-английски, сообщения об ошибках — по-английски (пользователь их видит в UI/логах).
* Не трогайте `three/package.json` (общий), не запускайте `npm install` (диск ограничен).
* В конце отчёта: список файлов, публичное API (сигнатуры), результаты тестов, известные ограничения.
* Проверяйте работу командами: `node --test ...` и короткими скриптами `node -e "import(...)"`.

## Общий контракт (его нельзя менять — на нём сходятся все модули)

```js
// packages/core/src/spec.mjs        (уже есть)
normalizeSpec(spec) -> spec          // заполняет умолчания, сортирует from/to
validateSpec(spec) -> {ok, errors[], warnings[]}
specBounds(spec) -> {min, max, size}
specDigest(spec, max) -> string      // компактный текст для промпта

// packages/core/src/materials.mjs   (уже есть)
DEFAULT_BLOCKS, resolveMaterial(material, palette, blocks) -> {block, family?, variants?}
```

Новые модули обязаны принимать/возвращать **только эти структуры** и не менять их форму.

## Пять параллельных заданий

### A. Геометрия сцены (`packages/core/src/geometry.mjs`, `packages/core/src/objects/*.mjs`)

Превращает `StructureSpec` в нормализованную геометрию для рендера И для вокселизации.

```js
buildScene(spec) -> Scene
// Scene = { spec, solids: Solid[], stats }
// Solid = { id, type, material, faces: Face[], voxels?: Set<key>, bounds }
// Face  = { v: [[x,y,z]×3|4], normal:[x,y,z], material, kind: 'surface'|'glass'|'detail' }
```

Требования:
* Реализовать все типы объектов из `TYPES` (`box, prism, cylinder, cone, sphere, ring, wall, floor, stairs,
  window, door, arch, balustrade, beam, column, chimney, detail`).
* Проёмы (`openings`, `window`, `door`, `arch`) **реально вырезают** геометрию из стен, а не рисуются поверх.
* Крыши: gable/hip/shed/pyramid с наклонными плоскостями, свесом (overhang) и закрытыми фронтонами.
* Дополнительно — **воксельное представление** каждого объекта: `voxelizeSolid(solid, options)` заполняет
  `Set<key>` ячеек 1×1×1 (`key = x|y|z` packed) по правилу «центр ячейки внутри объёма».
  Это нужно модулю переноса; экспортируйте `voxelizeSpec(spec) -> Map<key, {material, up, down, nx, ny, nz, solid}>`
  где `up/down` — есть ли твёрдая поверхность сверху/снизу ячейки, а `nx/ny/nz` — усреднённая нормаль
  (для выбора ступенек/полублоков).
* Детерминизм: одинаковый spec → одинаковая геометрия (никакого `Math.random`, кроме seeded PRNG с `spec.seed`).
* Производительность: дом 10×10×15 со всеми деталями — сборка < 300 мс.
* Тесты: каждый тип объекта даёт непустую геометрию; проём уменьшает число вокселей; gable-крыша замкнута;
  `voxelizeSpec` не даёт висящих вокселей для базового дома.

### B. Софтверный рендер (`packages/core/src/render.mjs`, `packages/core/src/png.mjs`)

```js
renderViews(scene, { width=800, height=600, views=['se','sw','ne','nw','front','top'], background='#eef3f8' })
  -> Promise<{ views: {name, png: Buffer, camera}[] }>
renderPng(scene, view, options) -> Promise<Buffer>
```

Требования:
* Перспективная камера, орбита вокруг модели, авто-подбор дистанции по bounding box, солнце + заполняющий свет,
  мягкие тени (или хотя бы градиент), прозрачность стекла, контур/Ambient occlusion — по возможности.
* Писать PNG самому (zlib + CRC32), без внешних зависимостей; корректный PNG для любого размера.
* Быстро: 400×300 ≤ 1.5 с на ракурс; поддержка «чернового» режима (масштаб 0.5, без теней).
* Тесты: PNG-сигнатура и `IHDR` размеры верны; картинка не однотонная (есть > 50 разных цветов);
  разные ракурсы дают разные картинки; рендер дома 10×10 не падает.

### C. Вокселизация и перенос в Minecraft (`packages/transfer/src/*.mjs`)

```js
transferToMinecraft(spec, { origin=[0,0,0], palette=..., options }) -> {
  blocks: Map<key, BlockState>,       // key = x|y|z в мировых координатах
  stats: { cells, blocks, byType: {...}, floating: n, stairs: n, slabs: n },
  warnings: []
}
toLitematic(blocks, meta) -> Buffer    // формат .litematic (Litematica), gzip NBT
toCommands(blocks) -> string[]         // /setblock … для быстрой установки
analyzeResult(blocks) -> {errors[], warnings[]}   // замкнутость, висящие блоки, опоры
```

Требования:
* Выбор блока: материал → `resolveMaterial`; наклонная поверхность (нормаль) → ступенька с `facing` по нормали;
  плоская сверху и снизу и тонкая → полублок (`type=top/bottom`); стекло → `glass_pane` с соединениями;
  столбик → забор. Состояния — валидные для 26.3.
* Никаких висящих блоков: постобработка добавляет опору или удаляет ячейку (как `OrientationFixer` в плагине).
* `toLitematic`: валидный NBT (TAG_Compound c `MinecraftDataVersion`, `Regions`, палитра блоков, `BlockStates`
  packed long array). Реализуйте NBT-писатель сами (`packages/transfer/src/nbt.mjs`), gzip через `node:zlib`.
  Валидируйте структуру собственным читателем в тесте (round-trip).
* Тесты: дом 10×10 из spec → воксели без висящих, замкнутая оболочка; `toLitematic` читается обратно
  (round-trip), размеры региона совпадают; `toCommands` даёт валидные строки.

### D. Веб-студия (`packages/studio/*`)

```js
// packages/studio/src/server.mjs — http-сервер без зависимостей (node:http)
start({ port=8137, root }) -> { url, close() }
// API: GET /api/builds, POST /api/build { prompt }, GET /api/build/:id, GET /api/build/:id/views
// packages/studio/public/index.html + app.js — three.js сцена в браузере
```

Требования:
* Просмотр модели в браузере на three.js (`import * as THREE from '/vendor/three.module.js'`, файл отдаётся
  сервером из `node_modules/three/build/`), OrbitControls — из `examples/jsm/controls/OrbitControls.js`.
* Панель итераций: список версий spec, кнопка «показать рендеры», сравнение двух версий рядом.
* Сервер отдаёт статику, JSON API и PNG-рендеры из `three/out/<buildId>/`.
* Реальный ИИ подключается позже — сейчас API может работать с заранее подготовленными spec-файлами
  (`three/samples/*.json`), это нормально.
* Тесты: сервер стартует на свободном порту, отдаёт index.html (200), `/vendor/three.module.js` (200, JS),
  API возвращает список построек.

### E. Оркестратор итераций (`packages/core/src/loop.mjs`, `packages/core/src/critic.mjs`)

```js
runBuildLoop({ prompt, spec0?, iterations=8, llm, render, thresholds }) -> { spec, history, best, report }
critiqueSpec(spec, views, analysis) -> { issues[], scores, verdict }   // ИИ-критик через llm
analyzeSpec(spec) -> { ok, metrics, issues[] }                        // объективные проверки
```

Требования:
* `llm` — внедряемая функция `({system, user, json}) => Promise<{content, usage}>`; в тестах — заглушка.
  Реального провайдера подключает архитектор, не вы.
* Каждая итерация: рендер → `analyzeSpec` (объективно) + `critiqueSpec` (ИИ по картинкам — передаём имена
  файлов ракурсов и текстовый дайджест) → правки spec (patch-операции ИИ: `add/replace/remove` объектов) →
  новая версия. Всё в `history[]` с метриками и причинами.
* Остановка: критик не нашёл major-замечаний И анализатор чист И прирост качества < порога 2 итерации подряд.
* `report` — markdown-отчёт: итерации, метрики, что менялось и почему (для пользователя и для блога).
* Тесты: с заглушкой `llm`, которая «улучшает» spec предсказуемо, цикл делает N итераций, останавливается по
  достижению порога, история полная, отчёт содержит все итерации.

## Точки стыка (не менять сигнатуры)

```js
// A -> B
buildScene(spec) -> Scene{spec, solids, stats}
// A -> C
voxelizeSpec(spec) -> Map<key, VoxelCell>
// B -> D/E
renderViews(scene, opts) -> {views:[{name, png, camera}]}
// C -> D
transferToMinecraft(spec, opts) / toLitematic(blocks, meta) / toCommands(blocks)
// E использует A, B, C
```

Если чего-то не хватает — добавляйте **новые** функции, не меняя существующие.
