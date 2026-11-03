package dev.codeverse.desktop;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.codeverse.analyzer.Dependencies;
import dev.codeverse.git.*;
import dev.codeverse.model.*;
import dev.codeverse.model.Data.*;
import dev.codeverse.render.CityRenderer;
import dev.codeverse.storage.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class CodeverseApp extends ApplicationAdapter {
  private static final Color BG = Color.valueOf("090E19"),
      PANEL = Color.valueOf("111B2D"),
      CYAN = Color.valueOf("4DE2E9"),
      TEXT = Color.valueOf("E4EDF6"),
      MUTED = Color.valueOf("8B9EB4");
  private Stage stage;
  private Skin skin;
  private CityRenderer city, comparisonCity;
  private boolean splitView = false;
  private Snapshot comparisonA;
  private ShapeRenderer lines;
  private final StableLayout layout = new StableLayout();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "codeverse-indexer");
            t.setDaemon(true);
            return t;
          });
  private final AtomicInteger generation = new AtomicInteger();
  private Future<?> job;
  private Store store;
  private Path repoPath;
  private Snapshot snapshot;
  private java.util.List<CommitInfo> history = java.util.List.of();
  private java.util.List<Change> changes = java.util.List.of();
  private java.util.List<Edge> edges = java.util.List.of();
  private Map<String, Hotspot> hotspots = Map.of();
  private Label status, repoLabel, metrics, commitLabel, inspector, header, diagnostics;
  private Table filesTable, side, inspect, top, bottom, inspectPanel;
  private TextField search;
  private SelectBox<String> branchA, branchB, window, depth, direction, speed;
  private Slider timeline, spacing;
  private CheckBox mergeBase, reduced, anonymous;
  private String selected, activeView = "Overview";
  private boolean busy = false,
      playing = false,
      diagnostic = false,
      suppress = false,
      follow = false,
      showEdges = false,
      dag = false;
  private int hotspotDays = 90;
  private float playClock = 0;
  private int currentCommit = 0;
  private final String initial;
  private ArrayDeque<Float> frameTimes = new ArrayDeque<>();
  private long indexStarted;
  private String[] refs = {"HEAD"};

  public static class Preferences {
    public String lastRepo = "";
    public java.util.List<String> recent = new ArrayList<>();
    public boolean reducedMotion = false;
    public boolean anonymous = false;
    public CityGenerator.Settings cityLayout = CityGenerator.Settings.defaults();
  }

  public static class Bookmark {
    public StableLayout.State layout;
    public String repo, sha, path;
    public float yaw, pitch, distance, x, y, z, spacing;
    public boolean exploded;
  }

  private Preferences preferences = new Preferences();
  private boolean guided = false;
  private float guidedTime = 0, captureClock = 0;
  private int guidedPhase = -1, captureIndex = 0;
  private Path captureDirectory;

  public void recordDemo(Path output) {
    captureDirectory = output;
    guided = true;
  }

  private static Path appData() {
    String override = System.getProperty("codeverse.data");
    if (override != null) return Path.of(override);
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT),
        home = System.getProperty("user.home");
    if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "CODEVERSE");
    if (os.contains("win"))
      return Path.of(System.getenv().getOrDefault("LOCALAPPDATA", home), "CODEVERSE");
    return Path.of(
        System.getenv().getOrDefault("XDG_DATA_HOME", home + "/.local/share"), "codeverse");
  }

  public void dropped(String[] files) {
    if (files.length > 0) open(Path.of(files[0]));
  }

  public CodeverseApp(String initial) {
    this.initial = initial;
  }

  @Override
  public void create() {
    stage = new Stage(new ScreenViewport());
    skin = createSkin();
    city = new CityRenderer();
    comparisonCity = new CityRenderer();
    lines = new ShapeRenderer();
    try {
      store = new Store(appData());
      preferences = store.load("settings", Preferences.class, new Preferences());
    } catch (Exception e) {
      System.err.println("Cache unavailable: " + e.getMessage());
    }
    city.reducedMotion = preferences.reducedMotion;
    buildUI();
    Gdx.input.setInputProcessor(
        new InputMultiplexer(
            stage,
            new InputAdapter() {
              int lx, ly;
              boolean dragging;

              public boolean touchDown(int x, int y, int pointer, int button) {
                lx = x;
                ly = y;
                dragging = false;
                return x > 264
                    && x < Gdx.graphics.getWidth() - 300
                    && y > 80
                    && y < Gdx.graphics.getHeight() - 122;
              }

              public boolean touchDragged(int x, int y, int pointer) {
                if (x > 264 && x < Gdx.graphics.getWidth() - 300) {
                  city.orbit(x - lx, y - ly);
                  dragging = true;
                  lx = x;
                  ly = y;
                }
                return true;
              }

              public boolean touchUp(int x, int y, int pointer, int button) {
                if (!dragging
                    && x > 264
                    && x < Gdx.graphics.getWidth() - 300
                    && y > 80
                    && y < Gdx.graphics.getHeight() - 122) select(pick(x, y));
                return true;
              }

              public boolean scrolled(float x, float y) {
                city.zoom(y);
                return true;
              }

              public boolean keyDown(int key) {
                if (key == Input.Keys.ESCAPE) {
                  stage.setKeyboardFocus(null);
                  return true;
                }
                if (key == Input.Keys.E) {
                  city.exploded = !city.exploded;
                  return true;
                }
                if (key == Input.Keys.R) {
                  city.reset();
                  return true;
                }
                if (key == Input.Keys.F) {
                  if (selected != null) city.focus(selected);
                  return true;
                }
                if (key == Input.Keys.T) {
                  city.tour = !city.tour;
                  return true;
                }
                if (key == Input.Keys.F3) {
                  diagnostic = !diagnostic;
                  return true;
                }
                if (key == Input.Keys.SPACE) {
                  playing = !playing;
                  return true;
                }
                if (key == Input.Keys.K
                    && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                        || Gdx.input.isKeyPressed(Input.Keys.SYM))) {
                  palette();
                  return true;
                }
                return false;
              }
            }));
    stage.addCaptureListener(
        new InputListener() {
          public boolean keyDown(InputEvent event, int key) {
            Actor focused = stage.getKeyboardFocus();
            if (focused instanceof CheckBox box
                && (key == Input.Keys.ENTER || key == Input.Keys.SPACE)) {
              box.setChecked(!box.isChecked());
              event.stop();
              return true;
            }
            if (focused instanceof Slider slider
                && (key == Input.Keys.LEFT || key == Input.Keys.RIGHT)) {
              slider.setValue(
                  slider.getValue()
                      + (key == Input.Keys.RIGHT ? slider.getStepSize() : -slider.getStepSize()));
              event.stop();
              return true;
            }
            if (key != Input.Keys.TAB) return false;
            java.util.List<Actor> controls = new ArrayList<>();
            Group root = stage.getRoot();
            for (Actor a : stage.getActors()) if (a instanceof Dialog dialog) root = dialog;
            collectControls(root, controls);
            if (controls.isEmpty()) return false;
            int index = controls.indexOf(stage.getKeyboardFocus()),
                step = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) ? -1 : 1;
            stage.setKeyboardFocus(controls.get(Math.floorMod(index + step, controls.size())));
            event.stop();
            return true;
          }
        });
    Path requested = initial == null ? null : Path.of(initial);
    if (requested != null) open(requested);
    else if (guided) demo();
    else if (!preferences.lastRepo.isBlank() && Files.exists(Path.of(preferences.lastRepo)))
      open(Path.of(preferences.lastRepo));
    else demo();
  }

  private Skin createSkin() {
    Skin s = new Skin();
    Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    p.setColor(Color.WHITE);
    p.fill();
    Texture t = new Texture(p);
    p.dispose();
    s.add("white", t);
    BitmapFont font = new BitmapFont();
    try {
      var generator = new FreeTypeFontGenerator(Gdx.files.internal("fonts/Roboto.ttf"));
      var param = new FreeTypeFontGenerator.FreeTypeFontParameter();
      param.size = 30;
      param.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ→●—·";
      font.dispose();
      font = generator.generateFont(param);
      font.getData().setScale(.5f);
      font.getRegion()
          .getTexture()
          .setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
      generator.dispose();
    } catch (Exception ignored) {
    }
    s.add("default-font", font);
    s.add("default", new Label.LabelStyle(font, TEXT));
    var button = new TextButton.TextButtonStyle();
    button.font = font;
    button.fontColor = TEXT;
    button.up = s.newDrawable("white", Color.valueOf("1B2B40"));
    button.over = s.newDrawable("white", Color.valueOf("29445B"));
    button.down = s.newDrawable("white", Color.valueOf("315B6B"));
    button.checked = s.newDrawable("white", Color.valueOf("254755"));
    button.disabledFontColor = MUTED;
    s.add("default", button);
    var tf = new TextField.TextFieldStyle();
    tf.font = font;
    tf.fontColor = TEXT;
    tf.background = s.newDrawable("white", Color.valueOf("0B1423"));
    tf.cursor = s.newDrawable("white", CYAN);
    tf.selection = s.newDrawable("white", Color.valueOf("355569"));
    tf.messageFontColor = MUTED;
    s.add("default", tf);
    var scroll = new ScrollPane.ScrollPaneStyle();
    scroll.vScroll = s.newDrawable("white", PANEL);
    scroll.vScrollKnob = s.newDrawable("white", Color.valueOf("36506A"));
    s.add("default", scroll);
    var list = new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle();
    list.font = font;
    list.fontColorSelected = CYAN;
    list.fontColorUnselected = TEXT;
    list.selection = s.newDrawable("white", Color.valueOf("29445B"));
    list.background = s.newDrawable("white", PANEL);
    s.add("default", list);
    var select = new SelectBox.SelectBoxStyle();
    select.font = font;
    select.fontColor = TEXT;
    select.background = s.newDrawable("white", Color.valueOf("1B2B40"));
    select.scrollStyle = scroll;
    select.listStyle = list;
    s.add("default", select);
    var slider = new Slider.SliderStyle();
    slider.background = s.newDrawable("white", Color.valueOf("284058"));
    slider.background.setMinHeight(3);
    slider.knob = s.newDrawable("white", CYAN);
    slider.knob.setMinWidth(12);
    slider.knob.setMinHeight(12);
    s.add("default-horizontal", slider);
    var cb = new CheckBox.CheckBoxStyle();
    cb.font = font;
    cb.fontColor = MUTED;
    cb.checkboxOff = s.newDrawable("white", Color.valueOf("284058"));
    cb.checkboxOn = s.newDrawable("white", CYAN);
    cb.checkboxOff.setMinWidth(12);
    cb.checkboxOff.setMinHeight(12);
    cb.checkboxOn.setMinWidth(12);
    cb.checkboxOn.setMinHeight(12);
    s.add("default", cb);
    var dialog = new Window.WindowStyle(font, TEXT, s.newDrawable("white", PANEL));
    s.add("default", dialog);
    return s;
  }

  private void collectControls(Group group, java.util.List<Actor> controls) {
    for (Actor actor : group.getChildren())
      if (actor.isVisible()) {
        if (actor instanceof TextButton
            || actor instanceof TextField
            || actor instanceof SelectBox<?>
            || actor instanceof Slider) controls.add(actor);
        else if (actor instanceof Group child) collectControls(child, controls);
      }
  }

  private Label label(String text, Color color) {
    Label l = new Label(text, skin);
    l.setColor(color);
    return l;
  }

  private TextButton button(String text, Runnable action) {
    TextButton b = new TextButton(text, skin);
    b.addListener(
        new InputListener() {
          public boolean keyDown(InputEvent event, int key) {
            if (key == Input.Keys.ENTER || key == Input.Keys.SPACE) {
              action.run();
              return true;
            }
            return false;
          }
        });
    b.addListener(
        new ChangeListener() {
          public void changed(ChangeEvent e, Actor a) {
            stage.setKeyboardFocus(null);
            action.run();
          }
        });
    return b;
  }

  private void on(Actor a, Runnable r) {
    a.addListener(
        new ChangeListener() {
          public void changed(ChangeEvent e, Actor actor) {
            if (!suppress) r.run();
          }
        });
  }

  private void buildUI() {
    top = new Table();
    top.setBackground(skin.newDrawable("white", PANEL));
    top.pad(18);
    stage.addActor(top);
    Label logo = label("C O D E V E R S E", CYAN);
    logo.setFontScale(.6f);
    top.add(logo).left().width(245);
    repoLabel = label("THE LIVING HISTORY OF CODE", MUTED);
    top.add(repoLabel).expandX().left();
    top.add(button("Open repository", this::openDialog)).height(36).width(155).padRight(10);
    top.add(button("Demo", this::demo)).height(36).width(65).padRight(8);
    top.add(
            button(
                "Guided tour",
                () -> {
                  guided = true;
                  guidedTime = 0;
                  guidedPhase = -1;
                  demo();
                }))
        .height(36)
        .width(100);
    side = new Table();
    side.top().left().pad(20);
    side.setBackground(skin.newDrawable("white", PANEL));
    stage.addActor(side);
    side.add(label("WORKSPACE", MUTED)).left().padBottom(15).row();
    for (String view :
        new String[] {"Overview", "Branches", "Timeline", "Dependencies", "Hotspots", "Bookmarks"})
      side.add(button(view, () -> view(view))).growX().height(35).padBottom(5).row();
    side.add(button("City generator", this::generatorDialog)).growX().height(35).padBottom(5).row();
    side.add(label("EXPLORE FILES", MUTED)).left().padTop(20).padBottom(12).row();
    search = new TextField("", skin);
    search.setMessageText("Search path / language");
    on(search, this::refreshFiles);
    side.add(search).growX().height(34).row();
    filesTable = new Table();
    filesTable.top().left();
    ScrollPane files = new ScrollPane(filesTable, skin);
    files.setFadeScrollBars(false);
    side.add(files).grow().padTop(12).row();
    side.add(button("Commands   Cmd/Ctrl + K", this::palette)).growX().height(34).padTop(12);
    inspect = new Table();
    inspect.top().left().pad(20);
    inspect.setBackground(skin.newDrawable("white", PANEL));
    inspectPanel = new Table();
    inspectPanel.setBackground(skin.newDrawable("white", PANEL));
    ScrollPane inspectorScroll = new ScrollPane(inspect, skin);
    inspectorScroll.setFadeScrollBars(false);
    inspectPanel.add(inspectorScroll).grow();
    stage.addActor(inspectPanel);
    inspect.add(label("INSPECTOR", MUTED)).left().padBottom(18).row();
    inspector =
        label(
            "Select a building\n"
                + "to inspect its source metrics.\n\n"
                + "Drag: orbit\n"
                + "Scroll: zoom\n"
                + "W A S D: fly\n"
                + "Shift: boost\n"
                + "F: focus   R: reset\n"
                + "E: explode   T: drone",
            TEXT);
    inspector.setWrap(true);
    inspect.add(inspector).width(260).left().padBottom(16).row();
    inspect
        .add(button("Source & file history", this::sourceDialog))
        .growX()
        .height(32)
        .padBottom(6)
        .row();
    inspect
        .add(
            button(
                "Focus selected   F",
                () -> {
                  if (selected != null) city.focus(selected);
                }))
        .growX()
        .height(32)
        .padBottom(6)
        .row();
    inspect
        .add(
            button(
                "Isolate module",
                () -> {
                  var b = city.selected();
                  if (b != null) city.isolate(b.file.module());
                }))
        .growX()
        .height(32)
        .padBottom(6)
        .row();
    inspect
        .add(button("Show all modules", () -> city.isolate(null)))
        .growX()
        .height(32)
        .padBottom(14)
        .row();
    inspect.add(label("SCENE CONTROLS", MUTED)).left().padBottom(12).row();
    Table modes = new Table();
    modes.add(button("Drone / T", () -> city.tour = !city.tour)).width(124).height(32).padRight(6);
    modes.add(button("Explode / E", () -> city.exploded = !city.exploded)).width(124).height(32);
    inspect.add(modes).row();
    spacing = new Slider(20, 180, 5, false, skin);
    spacing.setValue(85);
    on(spacing, () -> city.spacing = spacing.getValue());
    inspect.add(label("Layer spacing", MUTED)).left().padTop(12).row();
    inspect.add(spacing).growX().height(24).row();
    reduced = new CheckBox("  Reduced motion", skin);
    reduced.setChecked(preferences.reducedMotion);
    on(
        reduced,
        () -> {
          city.reducedMotion = reduced.isChecked();
          preferences.reducedMotion = city.reducedMotion;
          savePrefs();
        });
    inspect.add(reduced).left().padTop(12).row();
    anonymous = new CheckBox("  Anonymous export", skin);
    anonymous.setChecked(preferences.anonymous);
    on(
        anonymous,
        () -> {
          preferences.anonymous = anonymous.isChecked();
          savePrefs();
        });
    inspect.add(anonymous).left().padTop(8).row();
    inspect
        .add(button("Export screenshot", this::exportDialog))
        .growX()
        .height(34)
        .padTop(14)
        .row();
    inspect.add(button("Reset camera   R", city::reset)).growX().height(32).padTop(6).row();
    header = label("REPOSITORY ATLAS", TEXT);
    header.setFontScale(.65f);
    stage.addActor(header);
    metrics = label("LOCAL / OFFLINE / READ ONLY", MUTED);
    stage.addActor(metrics);
    diagnostics = label("", CYAN);
    stage.addActor(diagnostics);
    bottom = new Table();
    bottom.setBackground(skin.newDrawable("white", PANEL));
    bottom.pad(14, 20, 14, 20);
    stage.addActor(bottom);
    Table controls = new Table();
    controls.add(button("|<", () -> scrub(history.size() - 1))).width(42).height(30).padRight(6);
    controls
        .add(button("Play / Pause", () -> playing = !playing))
        .width(112)
        .height(30)
        .padRight(6);
    controls
        .add(button(">", () -> scrub(Math.max(0, currentCommit - 1))))
        .width(35)
        .height(30)
        .padRight(12);
    speed = new SelectBox<>(skin);
    speed.setItems("x1", "x4", "x16");
    controls.add(speed).width(55).height(30).padRight(16);
    timeline = new Slider(0, 1, 1, false, skin);
    on(
        timeline,
        () -> {
          if (!timeline.isDragging()) scrub((int) timeline.getValue());
        });
    timeline.addListener(
        new InputListener() {
          public void touchUp(InputEvent e, float x, float y, int p, int b) {
            scrub((int) timeline.getValue());
          }

          public boolean touchDown(InputEvent e, float x, float y, int p, int b) {
            return true;
          }
        });
    controls.add(timeline).growX().padRight(16);
    controls.add(button("Follow", () -> follow = !follow)).width(65).height(30);
    bottom.add(controls).growX().row();
    commitLabel = label("Preparing local workspace…", TEXT);
    bottom.add(commitLabel).growX().left().padTop(10).row();
    status = label("Ready", MUTED);
    bottom.add(status).growX().left().padTop(6);
    bottom.add(button("Cancel", this::cancel)).width(65).height(24);
    resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
  }

  private String pick(int x, int y) {
    int width = Gdx.graphics.getWidth() - 564, height = Gdx.graphics.getHeight() - 202;
    if (!splitView) return city.pick(x, y, 264, 122, width, height);
    if (x < 264 + width / 2) return comparisonCity.pick(x, y, 264, 122, width / 2, height);
    return city.pick(x, y, 264 + width / 2, 122, width - width / 2, height);
  }

  private void openDialog() {
    Dialog d = new Dialog("Open a local Git repository", skin);
    TextField path = new TextField(repoPath == null ? "" : repoPath.toString(), skin);
    path.setMessageText("/path/to/repository");
    d.getContentTable()
        .add(label("Repository root or bare repository. Read-only indexing.", MUTED))
        .pad(12)
        .row();
    d.getContentTable().add(path).width(640).height(38).pad(12).row();
    d.getContentTable()
        .add(
            button(
                "Choose folder…",
                () -> {
                  String chosen =
                      org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(
                          "Open Git repository", System.getProperty("user.home"));
                  if (chosen != null) path.setText(chosen);
                }))
        .height(34)
        .pad(12)
        .row();
    if (preferences.recent != null)
      for (String recent : preferences.recent)
        d.getContentTable()
            .add(
                button(
                    recent,
                    () -> {
                      d.hide();
                      open(Path.of(recent));
                    }))
            .width(640)
            .height(28)
            .pad(3)
            .row();
    d.getContentTable()
        .add(
            button(
                "Open",
                () -> {
                  d.hide();
                  open(Path.of(path.getText().strip()));
                }))
        .height(34)
        .pad(12);
    d.button("Cancel");
    d.show(stage);
    stage.setKeyboardFocus(path);
  }

  private void generatorDialog() {
    Dialog dialog = new Dialog("City generator", skin);
    var content = dialog.getContentTable();
    content.pad(18).defaults().pad(7);
    var current = layout.settings();
    TextField seed = new TextField(Long.toString(current.seed()), skin);
    SelectBox<String> style = new SelectBox<>(skin);
    style.setItems("Organic", "Downtown", "Garden");
    style.setSelectedIndex(current.style().ordinal());
    Slider scale = new Slider(.8f, 2.5f, .1f, false, skin);
    scale.setValue(current.scale());
    Label value = label(String.format(Locale.ROOT, "%.1fx", scale.getValue()), TEXT);
    on(scale, () -> value.setText(String.format(Locale.ROOT, "%.1fx", scale.getValue())));
    content.add(label("Seed", MUTED)).left();
    content.add(seed).width(240);
    content
        .add(
            button(
                "New seed",
                () -> seed.setText(Long.toString(new java.util.Random().nextInt(1000000000)))))
        .width(100)
        .row();
    content.add(label("District style", MUTED)).left();
    content.add(style).width(240).colspan(2).left().row();
    content.add(label("City scale", MUTED)).left();
    content.add(scale).width(240);
    content.add(value).row();
    Label explanation =
        label(
            "Organic: curved avenues and irregular blocks.\n"
                + "Downtown: denser street frontage. Garden: more parks.\n"
                + "The same seed recreates the same city. Generate moves parcels;\n"
                + "Git history and source files stay unchanged.",
            MUTED);
    content.add(explanation).colspan(3).left().padTop(12).row();
    Label error = label("", Color.SALMON);
    content.add(error).colspan(3).left().row();
    content
        .add(
            button(
                "Generate city",
                () -> {
                  if (snapshot == null || busy) {
                    error.setText("Open a repository and wait for indexing first.");
                    return;
                  }
                  long number;
                  try {
                    number = Long.parseLong(seed.getText().trim());
                  } catch (NumberFormatException e) {
                    error.setText("Seed must be a whole number.");
                    return;
                  }
                  playing = false;
                  guided = false;
                  city.tour = false;
                  splitView = false;
                  layout.configure(
                      new CityGenerator.Settings(
                          number,
                          CityGenerator.Style.values()[style.getSelectedIndex()],
                          scale.getValue()));
                  preferences.cityLayout = layout.settings();
                  savePrefs();
                  display(true);
                  dialog.hide();
                  status.setText(
                      "Generated city / seed "
                          + number
                          + " / "
                          + layout.plan().blocks().size()
                          + " blocks");
                }))
        .height(38)
        .width(180)
        .colspan(3)
        .padTop(10)
        .row();
    dialog.button("Close");
    dialog.show(stage);
  }

  private void demo() {
    runTask(
        "Preparing demo repository",
        () -> {
          Path home =
              store == null
                  ? Path.of(System.getProperty("java.io.tmpdir"), "codeverse")
                  : store.home();
          return DemoRepository.create(home.resolve("demo-city-v3"));
        },
        this::open);
  }

  private void open(Path path) {
    playing = false;
    splitView = false;
    runTask(
        "Indexing " + path.getFileName(),
        () -> {
          try (var index = new RepositoryIndex(path)) {
            int token = generation.get();
            var h = index.history("HEAD", true, () -> generation.get() != token);
            Snapshot s = cached(index, "HEAD", token);
            var hot = index.hotspots("HEAD", 90, () -> generation.get() != token);
            return new Loaded(s, h, index.refs(), hot);
          }
        },
        data -> {
          repoPath = path;
          selected = null;
          layout.configure(
              preferences.cityLayout == null
                  ? CityGenerator.Settings.defaults()
                  : preferences.cityLayout);
          snapshot = data.snapshot;
          history = data.history;
          refs = data.refs.toArray(String[]::new);
          hotspots = data.hotspots;
          hotspotDays = 90;
          changes = java.util.List.of();
          currentCommit = 0;
          repoLabel.setText(path.getFileName() + "  /  LOCAL");
          preferences.lastRepo = path.toString();
          if (preferences.recent == null) preferences.recent = new ArrayList<>();
          preferences.recent.remove(path.toString());
          preferences.recent.addFirst(path.toString());
          if (preferences.recent.size() > 8) preferences.recent.removeLast();
          savePrefs();
          display(true);
        });
  }

  private record Loaded(
      Snapshot snapshot,
      java.util.List<CommitInfo> history,
      java.util.List<String> refs,
      Map<String, Hotspot> hotspots) {}

  private Snapshot cached(RepositoryIndex index, String ref, int token) throws Exception {
    String sha = index.resolve(ref), id = index.identity();
    Snapshot s = null;
    if (store != null)
      try {
        s = store.get(id, sha);
      } catch (Exception e) {
        System.err.println(e.getMessage());
      }
    if (s == null) {
      s = index.snapshot(sha, () -> generation.get() != token, msg -> postStatus(msg));
      if (store != null)
        try {
          store.put(id, s);
        } catch (Exception e) {
          System.err.println(e.getMessage());
        }
    }
    return s;
  }

  private <T> void runTask(String text, Callable<T> action, Consumer<T> done) {
    int token = generation.incrementAndGet();
    if (job != null) job.cancel(true);
    busy = true;
    indexStarted = System.nanoTime();
    status.setText(text + "…");
    job =
        worker.submit(
            () -> {
              try {
                T value = action.call();
                Gdx.app.postRunnable(
                    () -> {
                      if (token != generation.get()) return;
                      busy = false;
                      done.accept(value);
                      status.setText(
                          "Ready / "
                              + String.format(
                                  Locale.ROOT, "%.2f s", (System.nanoTime() - indexStarted) / 1e9)
                              + " / no network requests");
                    });
              } catch (Exception e) {
                Gdx.app.postRunnable(
                    () -> {
                      if (token != generation.get()) return;
                      busy = false;
                      playing = false;
                      status.setText("Error: " + e.getMessage());
                    });
              }
            });
  }

  private void cancel() {
    generation.incrementAndGet();
    if (job != null) job.cancel(true);
    busy = false;
    playing = false;
    status.setText("Indexing cancelled");
  }

  private void postStatus(String text) {
    Gdx.app.postRunnable(
        () -> {
          if (busy) status.setText(text);
        });
  }

  private void display(boolean reset) {
    edges = Dependencies.analyze(snapshot);
    Snapshot visual = Aggregation.forRendering(snapshot);
    layout.reserve(visual.files().stream().map(FileRevision::path).toList());
    city.setPlan(layout.plan());
    city.setScene(visual, layout.cells(), changes, hotspots, reset);
    if (follow && selected != null) city.focus(selected);
    metrics.setText(
        snapshot.files().size()
            + " FILES   /   "
            + snapshot.files().stream().map(FileRevision::module).distinct().count()
            + " MODULES   /   "
            + edges.size()
            + " IMPORT EDGES"
            + (visual != snapshot ? " / AGGREGATED" : ""));
    suppress = true;
    timeline.setRange(0, Math.max(1, history.size() - 1));
    timeline.setValue(currentCommit);
    suppress = false;
    CommitInfo c =
        history.stream().filter(x -> x.sha().equals(snapshot.sha())).findFirst().orElse(null);
    commitLabel.setText(
        snapshot.sha().substring(0, 8)
            + "  "
            + (c == null ? "Branch comparison" : c.message() + "  /  " + c.author())
            + "  /  "
            + (dag ? "DAG" : "first-parent")
            + (c != null && c.parents().size() > 1 ? "  MERGE" : ""));
    refreshFiles();
    select(selected);
  }

  private void refreshFiles() {
    if (filesTable == null) return;
    filesTable.clear();
    if (snapshot == null) return;
    String q = search.getText().toLowerCase(Locale.ROOT);
    var stream =
        snapshot.files().stream()
            .filter(
                f ->
                    fuzzy(q, f.path().toLowerCase(Locale.ROOT))
                        || (!q.isEmpty() && f.language().toLowerCase(Locale.ROOT).contains(q)));
    if (activeView.equals("Hotspots"))
      stream =
          stream.sorted(
              Comparator.comparingDouble(
                  (FileRevision f) ->
                      -hotspots.getOrDefault(f.path(), new Hotspot(0, 0, 0, 0)).score()));
    for (var f : stream.limit(250).toList()) {
      String name = f.path().substring(f.path().lastIndexOf('/') + 1);
      if (name.length() > 24) name = name.substring(0, 21) + "…";
      filesTable
          .add(
              button(
                  name,
                  () -> {
                    select(f.path());
                    city.focus(f.path());
                  }))
          .width(213)
          .height(29)
          .padBottom(4)
          .row();
    }
  }

  private static boolean fuzzy(String q, String s) {
    int j = 0;
    for (int i = 0; i < s.length() && j < q.length(); i++) if (s.charAt(i) == q.charAt(j)) j++;
    return j == q.length();
  }

  private FileRevision selectedRevision() {
    var building = city.selected();
    if (building != null) return building.file;
    if (snapshot == null || selected == null) return null;
    return snapshot.files().stream()
        .filter(file -> file.path().equals(selected))
        .findFirst()
        .orElse(null);
  }

  private void select(String path) {
    selected = path;
    city.select(path);
    comparisonCity.select(path);
    var b = city.selected();
    var f = selectedRevision();
    if (f == null) {
      inspector.setText(
          "Select a building\n"
              + "to inspect its source metrics.\n\n"
              + "Drag: orbit  /  scroll: zoom\n"
              + "WASD: fly  /  Shift: boost\n"
              + "F: focus  /  R: reset\n"
              + "E: explode  /  T: drone");
      city.highlight(Set.of());
      return;
    }
    var h = hotspots.getOrDefault(path, new Hotspot(0, 0, 0, 0));
    var profile = dev.codeverse.model.BuildingProfile.analyze(f);
    inspector.setText(
        f.path()
            + "\n\n"
            + f.language()
            + " / "
            + f.kind()
            + "\n"
            + f.loc()
            + " lines  /  "
            + f.bytes()
            + " bytes\n\nHEIGHT\nclamp(8 + 16 × log2(1+LOC),\n8, 120) = "
            + Math.round(Data.height(f.loc()))
            + "\n\nBUILDING MODEL\n"
            + profile.model().label
            + "\nRichness: "
            + profile.richness()
            + "/100"
            + "\nCompactness: "
            + profile.compactness()
            + "/100"
            + "\nLexical signals, not code quality.\nComments and strings excluded."
            + "\n\nCHANGE FREQUENCY\n"
            + h.commits()
            + " commits / "
            + h.authors()
            + " authors\n"
            + h.linesChanged()
            + " lines changed\nWindow: "
            + hotspotDays
            + " days\nRef: HEAD at last calculation\nHistory cap: 2,000 commits\nScore: "
            + String.format(Locale.ROOT, "%.3f", h.score())
            + " (normalized)\n"
            + (b == null || b.change.isEmpty() ? "" : "\nDIFF: " + b.change));
    if (showEdges)
      city.highlight(
          Dependencies.reach(
              edges,
              path,
              depth == null ? 1 : Integer.parseInt(depth.getSelected()),
              direction != null && direction.getSelected().equals("Imported by")));
  }

  private void scrub(int index) {
    if (history.isEmpty() || repoPath == null || index < 0 || index >= history.size()) return;
    splitView = false;
    currentCommit = index;
    String ref = history.get(index).sha();
    Path path = repoPath;
    String before = snapshot == null ? ref : snapshot.sha();
    runTask(
        "Loading commit " + ref.substring(0, 8),
        () -> {
          try (var repo = new RepositoryIndex(path)) {
            int token = generation.get();
            return new Compared(cached(repo, ref, token), repo.diff(before, ref, false));
          }
        },
        v -> {
          snapshot = v.snapshot;
          changes = v.changes;
          display(false);
        });
  }

  private record Compared(Snapshot snapshot, java.util.List<Change> changes) {}

  private void view(String name) {
    activeView = name;
    header.setText(name.equals("Overview") ? "REPOSITORY ATLAS" : name.toUpperCase(Locale.ROOT));
    city.heat = name.equals("Hotspots");
    showEdges = name.equals("Dependencies");
    refreshFiles();
    switch (name) {
      case "Branches" -> branches();
      case "Timeline" -> historyDialog();
      case "Dependencies" -> dependencyDialog();
      case "Hotspots" -> hotspotDialog();
      case "Bookmarks" -> bookmarks();
      default -> {}
    }
  }

  private void branches() {
    Dialog d = new Dialog("Branch universe / A → B", skin);
    branchA = new SelectBox<>(skin);
    branchA.setItems(refs);
    branchB = new SelectBox<>(skin);
    branchB.setItems(refs);
    if (refs.length > 1) branchB.setSelectedIndex(1);
    mergeBase = new CheckBox("  Compare merge-base(A,B) → B (A...B)", skin);
    Table c = d.getContentTable();
    c.add(label("A", MUTED)).pad(12);
    c.add(branchA).width(250).height(34);
    c.add(label("B", MUTED)).pad(12);
    c.add(branchB).width(250).height(34).row();
    c.add(mergeBase).colspan(4).left().pad(14).row();
    c.add(
            label(
                "Default: tip-to-tip A → B (git diff A B). Stable overlay positions.\n"
                    + "Cyan: added / violet: modified or renamed / red: deleted.",
                MUTED))
        .colspan(4)
        .pad(14)
        .row();
    c.add(
            button(
                "Compare overlay",
                () -> {
                  String a = branchA.getSelected(), b = branchB.getSelected();
                  boolean base = mergeBase.isChecked();
                  splitView = false;
                  d.hide();
                  compare(a, b, base);
                }))
        .colspan(2)
        .height(36)
        .pad(14);
    c.add(
            button(
                "Split A / B",
                () -> {
                  String a = branchA.getSelected(), b = branchB.getSelected();
                  boolean base = mergeBase.isChecked();
                  splitView = true;
                  d.hide();
                  compare(a, b, base);
                }))
        .colspan(2)
        .height(36)
        .pad(14);
    d.button("Close");
    d.show(stage);
  }

  private record BranchComparison(
      Snapshot a, Snapshot b, java.util.List<Change> changes, java.util.List<CommitInfo> history) {}

  private void compare(String a, String b, boolean base) {
    if (repoPath == null) return;
    Path path = repoPath;
    runTask(
        "Comparing " + a + " → " + b,
        () -> {
          try (var repo = new RepositoryIndex(path)) {
            int token = generation.get();
            Snapshot sa = cached(repo, base ? repo.mergeBase(a, b) : a, token),
                sb = cached(repo, b, token);
            return new BranchComparison(
                sa,
                sb,
                repo.diff(a, b, base),
                repo.history(b, true, () -> generation.get() != token));
          }
        },
        v -> {
          comparisonA = v.a;
          changes = v.changes;
          history = v.history;
          currentCommit = 0;
          dag = false;
          Map<String, FileRevision> union = new TreeMap<>();
          for (var f : v.b.files()) union.put(f.path(), f);
          Set<String> deleted = new HashSet<>();
          for (var x : changes) if (x.type().equals("DELETE")) deleted.add(x.oldPath());
          for (var f : v.a.files()) if (deleted.contains(f.path())) union.putIfAbsent(f.path(), f);
          layout.reserve(v.a.files().stream().map(FileRevision::path).toList());
          layout.reserve(v.b.files().stream().map(FileRevision::path).toList());
          snapshot = new Snapshot(v.b.sha(), java.util.List.copyOf(union.values()));
          display(false);
          if (splitView) {
            comparisonCity.setPlan(layout.plan());
            city.setPlan(layout.plan());
            comparisonCity.setScene(v.a, layout.cells(), java.util.List.of(), Map.of(), true);
            city.setScene(v.b, layout.cells(), changes, hotspots, true);
            city.distance *= 1.6f;
          }
          header.setText(splitView ? "BRANCH UNIVERSE / A | B" : "BRANCH OVERLAY");
          metrics.setText(
              a
                  + " → "
                  + b
                  + " / "
                  + changes.size()
                  + " CHANGES / "
                  + (base ? "MERGE-BASE" : "TIP-TO-TIP"));
        });
  }

  private void historyDialog() {
    Dialog d = new Dialog("Commit history / bounded to 2,000 commits", skin);
    Table rows = new Table();
    rows.top().left();
    for (int i = 0; i < history.size(); i++) {
      int n = i;
      var c = history.get(i);
      rows.add(
              button(
                  c.sha().substring(0, 7)
                      + "  "
                      + (c.parents().size() > 1 ? "MERGE  " : "")
                      + c.message(),
                  () -> {
                    d.hide();
                    scrub(n);
                  }))
          .width(650)
          .height(32)
          .padBottom(4)
          .row();
      if (dag)
        rows.add(
                label(
                    "parents: "
                        + String.join(
                            ", ", c.parents().stream().map(p -> p.substring(0, 7)).toList()),
                    MUTED))
            .left()
            .row();
    }
    d.getContentTable().add(new ScrollPane(rows, skin)).width(700).height(400).row();
    d.getContentTable()
        .add(
            button(
                dag ? "Switch to first-parent" : "Show full DAG topology",
                () -> {
                  d.hide();
                  if (repoPath == null) return;
                  dag = !dag;
                  Path p = repoPath;
                  runTask(
                      "Reading commit topology",
                      () -> {
                        try (var repo = new RepositoryIndex(p)) {
                          int token = generation.get();
                          return repo.history("HEAD", !dag, () -> generation.get() != token);
                        }
                      },
                      h -> {
                        history = h;
                        historyDialog();
                      });
                }))
        .height(34)
        .pad(12);
    d.button("Close");
    d.show(stage);
  }

  private void dependencyDialog() {
    Dialog d = new Dialog("Dependency X-Ray", skin);
    depth = new SelectBox<>(skin);
    depth.setItems("1", "2", "3");
    direction = new SelectBox<>(skin);
    direction.setItems("Imports", "Imported by");
    var c = d.getContentTable();
    c.add(label("Depth", MUTED)).pad(12);
    c.add(depth).width(60).height(32);
    c.add(direction).width(170).height(32).pad(12).row();
    c.add(
            label(
                "Java imports only / heuristic confidence 0.8.\n"
                    + "Reflection, runtime calls and other languages are unknown.\n"
                    + "Select a building to highlight reachable files.",
                MUTED))
        .colspan(3)
        .pad(15)
        .row();
    c.add(
            button(
                "Impact preview",
                () -> {
                  Set<String> impact = new HashSet<>();
                  for (var change : changes) {
                    String file =
                        change.type().equals("DELETE") ? change.oldPath() : change.newPath();
                    impact.addAll(Dependencies.reach(edges, file, 3, true));
                  }
                  city.highlight(impact);
                  inspector.setText(
                      "Potential impact (heuristic)\n"
                          + impact.size()
                          + " dependent files\n"
                          + "Depth: 3 / imported by\n\n"
                          + "This is not a prediction of errors.");
                  d.hide();
                }))
        .colspan(3)
        .height(34)
        .pad(12)
        .row();
    c.add(
            button(
                "Apply",
                () -> {
                  select(selected);
                  d.hide();
                }))
        .height(34)
        .pad(12);
    c.add(
            button(
                "Find cycle members",
                () -> {
                  var currentEdges = edges;
                  d.hide();
                  runTask(
                      "Finding import cycles",
                      () -> Dependencies.cycles(currentEdges),
                      cycle -> {
                        city.highlight(cycle);
                        inspector.setText(
                            cycle.size()
                                + " files in import cycles\n\n"
                                + String.join("\n", cycle.stream().sorted().limit(12).toList()));
                      });
                }))
        .colspan(2)
        .height(34)
        .pad(12);
    d.button("Close");
    d.show(stage);
  }

  private void hotspotDialog() {
    Dialog d = new Dialog("Change frequency / not a quality verdict", skin);
    window = new SelectBox<>(skin);
    window.setItems("90", "30", "365");
    d.getContentTable().add(label("Window (days)", MUTED)).pad(12);
    d.getContentTable().add(window).width(100).height(32).row();
    d.getContentTable()
        .add(
            label(
                "normalized(commits × .55 + lines changed × .30 + authors × .15)\n"
                    + "First-parent history, maximum 2,000 commits.",
                MUTED))
        .colspan(2)
        .pad(12)
        .row();
    d.getContentTable()
        .add(
            button(
                "Recalculate",
                () -> {
                  int days = Integer.parseInt(window.getSelected());
                  d.hide();
                  if (repoPath == null) return;
                  Path p = repoPath;
                  runTask(
                      "Calculating hotspots",
                      () -> {
                        try (var r = new RepositoryIndex(p)) {
                          int token = generation.get();
                          return r.hotspots("HEAD", days, () -> generation.get() != token);
                        }
                      },
                      h -> {
                        hotspots = h;
                        hotspotDays = days;
                        display(false);
                      });
                }))
        .colspan(2)
        .height(34)
        .pad(12);
    d.button("Close");
    d.show(stage);
  }

  private void bookmarks() {
    Dialog d = new Dialog("Saved viewpoint", skin);
    d.getContentTable()
        .add(
            button(
                "Save current view",
                () -> {
                  if (store == null || snapshot == null) return;
                  Bookmark b = new Bookmark();
                  b.layout = layout.state();
                  b.repo = repoPath.toString();
                  b.sha = snapshot.sha();
                  b.path = selected;
                  b.yaw = city.yaw;
                  b.pitch = city.pitch;
                  b.distance = city.distance;
                  b.x = city.target().x;
                  b.y = city.target().y;
                  b.z = city.target().z;
                  b.exploded = city.exploded;
                  b.spacing = city.spacing;
                  try {
                    store.save("bookmark", b);
                    status.setText("Viewpoint saved locally");
                  } catch (Exception e) {
                    status.setText(e.getMessage());
                  }
                  d.hide();
                }))
        .width(200)
        .height(36)
        .pad(12);
    d.getContentTable()
        .add(
            button(
                "Restore saved view",
                () -> {
                  d.hide();
                  if (store == null) return;
                  Bookmark b = store.load("bookmark", Bookmark.class, null);
                  if (b == null) {
                    status.setText("No saved viewpoint");
                    return;
                  }
                  repoPath = Path.of(b.repo);
                  runTask(
                      "Restoring viewpoint",
                      () -> {
                        try (var r = new RepositoryIndex(repoPath)) {
                          int token = generation.get();
                          return new Loaded(
                              cached(r, b.sha, token),
                              r.history(b.sha, true, () -> generation.get() != token),
                              r.refs(),
                              r.hotspots(b.sha, 90, () -> generation.get() != token));
                        }
                      },
                      v -> {
                        if (b.layout != null) layout.restore(b.layout);
                        else layout.clear();
                        snapshot = v.snapshot;
                        history = v.history;
                        refs = v.refs.toArray(String[]::new);
                        hotspots = v.hotspots;
                        changes = java.util.List.of();
                        display(true);
                        if (b.layout == null || b.layout.version() == StableLayout.VERSION) {
                          city.yaw = b.yaw;
                          city.pitch = b.pitch;
                          city.distance = b.distance;
                          city.target().set(b.x, b.y, b.z);
                          city.exploded = b.exploded;
                          city.spacing = b.spacing;
                        }
                        select(b.path);
                        repoLabel.setText(repoPath.getFileName() + " / LOCAL");
                      });
                }))
        .width(200)
        .height(36)
        .pad(12);
    d.button("Close");
    d.show(stage);
  }

  private void sourceDialog() {
    var revision = selectedRevision();
    if (revision == null) return;
    String reference = snapshot.sha();
    Dialog d = new Dialog(revision.path(), skin);
    Label source =
        new Label(
            revision.source().isEmpty()
                ? "Source unavailable for " + revision.kind()
                : revision.source().substring(0, Math.min(100000, revision.source().length())),
            skin);
    d.getContentTable().add(new ScrollPane(source, skin)).width(780).height(440).pad(12).row();
    d.getContentTable()
        .add(
            button(
                "File commits",
                () -> {
                  d.hide();
                  Path p = repoPath;
                  String file = revision.path();
                  runTask(
                      "Reading file history",
                      () -> {
                        try (var idx = new RepositoryIndex(p)) {
                          return idx.fileHistory(reference, file);
                        }
                      },
                      commits -> {
                        Dialog h =
                            new Dialog("File history / exact path (renames not followed)", skin);
                        Table rows = new Table();
                        for (var c : commits)
                          rows.add(label(c.sha().substring(0, 8) + "  " + c.message(), TEXT))
                              .left()
                              .pad(6)
                              .row();
                        h.getContentTable().add(new ScrollPane(rows, skin)).width(700).height(400);
                        h.button("Close");
                        h.show(stage);
                      });
                }))
        .height(34)
        .pad(12);
    d.button("Close");
    d.show(stage);
  }

  private void palette() {
    Dialog d = new Dialog("Command palette", skin);
    String[] names = {
      "Open repository",
      "Go to file",
      "Branch A/B",
      "Drone View",
      "Explode",
      "Reset camera",
      "Focus hotspot",
      "Export screenshot"
    };
    for (String name : names)
      d.getContentTable()
          .add(
              button(
                  name,
                  () -> {
                    d.hide();
                    switch (name) {
                      case "Open repository" -> openDialog();
                      case "Go to file" -> stage.setKeyboardFocus(search);
                      case "Branch A/B" -> branches();
                      case "Drone View" -> city.tour = !city.tour;
                      case "Explode" -> city.exploded = !city.exploded;
                      case "Reset camera" -> city.reset();
                      case "Focus hotspot" ->
                          hotspots.entrySet().stream()
                              .max(Comparator.comparingDouble(e -> e.getValue().score()))
                              .ifPresent(
                                  e -> {
                                    select(e.getKey());
                                    city.focus(e.getKey());
                                  });
                      case "Export screenshot" -> exportDialog();
                    }
                  }))
          .width(320)
          .height(36)
          .pad(4)
          .row();
    d.button("Close");
    d.show(stage);
  }

  private void exportDialog() {
    Dialog d = new Dialog("Export screenshot", skin);
    d.getContentTable()
        .add(
            label(
                "Exports may include repository paths and author names.\n"
                    + "Anonymous export captures only the 3D viewport.",
                MUTED))
        .pad(20)
        .row();
    d.getContentTable()
        .add(
            button(
                "Save PNG",
                () -> {
                  d.hide();
                  Gdx.app.postRunnable(() -> Gdx.app.postRunnable(this::screenshot));
                }))
        .height(36)
        .pad(12);
    d.button("Cancel");
    d.show(stage);
  }

  private void screenshot() {
    try {
      Path dir =
          (store == null
                  ? Path.of(System.getProperty("java.io.tmpdir"), "codeverse")
                  : store.home())
              .resolve("exports");
      Files.createDirectories(dir);
      Path dest = dir.resolve("codeverse-" + System.currentTimeMillis() + ".png");
      int x = anonymous.isChecked() ? HdpiUtils.toBackBufferX(264) : 0,
          y = anonymous.isChecked() ? HdpiUtils.toBackBufferY(122) : 0,
          w =
              anonymous.isChecked()
                  ? HdpiUtils.toBackBufferX(Gdx.graphics.getWidth() - 564)
                  : Gdx.graphics.getBackBufferWidth(),
          h =
              anonymous.isChecked()
                  ? HdpiUtils.toBackBufferY(Gdx.graphics.getHeight() - 202)
                  : Gdx.graphics.getBackBufferHeight();
      Pixmap pix = Pixmap.createFromFrameBuffer(x, y, w, h);
      PixmapIO.writePNG(Gdx.files.absolute(dest.toString()), pix, -1, true);
      pix.dispose();
      status.setText("Saved: " + dest);
    } catch (Exception e) {
      status.setText("Export failed: " + e.getMessage());
    }
  }

  private void savePrefs() {
    if (store != null)
      try {
        store.save("settings", preferences);
      } catch (Exception e) {
        status.setText("Settings: " + e.getMessage());
      }
  }

  @Override
  public void render() {
    float dt = Math.min(.05f, Gdx.graphics.getDeltaTime());
    if (guided) advanceDemo(dt);
    frameTimes.add(Gdx.graphics.getDeltaTime() * 1000);
    if (frameTimes.size() > 300) frameTimes.remove();
    if (stage.getKeyboardFocus() == null) {
      float
          f =
              (Gdx.input.isKeyPressed(Input.Keys.W) ? 1 : 0)
                  - (Gdx.input.isKeyPressed(Input.Keys.S) ? 1 : 0),
          r =
              (Gdx.input.isKeyPressed(Input.Keys.D) ? 1 : 0)
                  - (Gdx.input.isKeyPressed(Input.Keys.A) ? 1 : 0);
      city.move(f, r, dt, Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT));
    }
    if (playing && !busy && !history.isEmpty()) {
      playClock += dt;
      float delay =
          speed.getSelected().equals("x16") ? .18f : speed.getSelected().equals("x4") ? .5f : 1.6f;
      if (playClock > delay) {
        playClock = 0;
        scrub(currentCommit > 0 ? currentCommit - 1 : history.size() - 1);
      }
    }
    ScreenUtils.clear(BG, true);
    int sceneWidth = Math.max(1, Gdx.graphics.getWidth() - 564),
        sceneHeight = Math.max(1, Gdx.graphics.getHeight() - 202);
    if (splitView) {
      comparisonCity.yaw = city.yaw;
      comparisonCity.pitch = city.pitch;
      comparisonCity.distance = city.distance;
      comparisonCity.target().set(city.target());
      comparisonCity.exploded = city.exploded;
      comparisonCity.spacing = city.spacing;
      comparisonCity.reducedMotion = city.reducedMotion;
      comparisonCity.render(dt, 264, 122, sceneWidth / 2, sceneHeight);
      city.render(dt, 264 + sceneWidth / 2, 122, sceneWidth - sceneWidth / 2, sceneHeight);
    } else city.render(dt, 264, 122, sceneWidth, sceneHeight);
    if (showEdges && selected != null && !splitView) drawEdges();
    stage.getViewport().apply();
    stage.act(dt);
    stage.draw();
    drawLabels();
    if (captureDirectory != null && guided) captureDemoFrame(dt);
    diagnostics.setVisible(diagnostic);
    if (diagnostic) {
      var samples = frameTimes.stream().sorted().toList();
      float p95 = samples.get((int) (samples.size() * .95));
      diagnostics.setText(
          Gdx.graphics.getFramesPerSecond()
              + " FPS / p95 "
              + String.format(Locale.ROOT, "%.1f", p95)
              + " ms / heap "
              + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576
              + " MB / "
              + city.buildings().size()
              + " nodes");
    }
  }

  private void advanceDemo(float dt) {
    if (busy || snapshot == null || history.isEmpty()) return;
    guidedTime += dt;
    int phase = (int) (guidedTime / 5);
    if (phase == guidedPhase) return;
    guidedPhase = phase;
    switch (phase) {
      case 0 -> {
        city.reset();
        city.tour = false;
        city.exploded = false;
        header.setText("01 / EXPLORE YOUR CODE");
      }
      case 1 -> {
        city.tour = true;
        header.setText("02 / DRONE TOUR");
      }
      case 2 -> {
        city.tour = false;
        city.reset();
        scrub(history.size() - 1);
        header.setText("03 / TRAVEL THROUGH HISTORY");
      }
      case 3 -> {
        scrub(0);
        header.setText("04 / WATCH THE CITY GROW");
      }
      case 4 -> {
        splitView = false;
        compare("main", "feature/observability", false);
      }
      case 5 -> {
        city.exploded = true;
        header.setText("06 / EXPLODED ARCHITECTURE");
      }
      case 6 -> {
        city.exploded = false;
        splitView = true;
        compare("main", "feature/observability", false);
      }
      default -> {
        guided = false;
        city.tour = false;
        city.exploded = false;
        splitView = false;
        city.reset();
        header.setText("REPOSITORY ATLAS");
        status.setText("Demo complete / Open your own repository to explore");
        if (captureDirectory != null) Gdx.app.exit();
      }
    }
  }

  private void captureDemoFrame(float dt) {
    captureClock += dt;
    if (captureClock < .1f) return;
    captureClock = 0;
    try {
      Files.createDirectories(captureDirectory);
      Pixmap full =
          Pixmap.createFromFrameBuffer(
              0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
      if (guidedTime < 12) {
        Pixmap small = new Pixmap(960, 600, Pixmap.Format.RGB888);
        small.setFilter(Pixmap.Filter.BiLinear);
        small.drawPixmap(full, 0, 0, full.getWidth(), full.getHeight(), 0, 0, 960, 600);
        PixmapIO.writePNG(
            Gdx.files.absolute(
                captureDirectory
                    .resolve(String.format(Locale.ROOT, "frame-%04d.png", captureIndex++))
                    .toString()),
            small,
            -1,
            true);
        small.dispose();
      }
      if ((guidedTime > 2 && guidedTime < 2.2)
          || (guidedTime > 22 && guidedTime < 22.2)
          || (guidedTime > 27 && guidedTime < 27.2)
          || (guidedTime > 32 && guidedTime < 32.2))
        PixmapIO.writePNG(
            Gdx.files.absolute(
                captureDirectory.resolve("scene-" + guidedPhase + ".png").toString()),
            full,
            -1,
            true);
      full.dispose();
    } catch (Exception e) {
      status.setText("Capture failed: " + e.getMessage());
      captureDirectory = null;
    }
  }

  private void drawLabels() {
    if (splitView || anonymous.isChecked()) return;
    var batch = stage.getBatch();
    BitmapFont font = skin.getFont("default-font");
    batch.begin();
    font.setColor(MUTED);
    if (selected != null) {
      var b = city.selected();
      if (b != null) {
        Vector3 v = new Vector3();
        b.bounds.getCenter(v);
        v.y = b.bounds.max.y + 10;
        city.camera.project(
            v, 264, 122, Gdx.graphics.getWidth() - 564, Gdx.graphics.getHeight() - 202);
        if (v.x > 280
            && v.x < Gdx.graphics.getWidth() - 310
            && v.y > 160
            && v.y < Gdx.graphics.getHeight() - 170)
          font.draw(batch, b.file.path().substring(b.file.path().lastIndexOf('/') + 1), v.x, v.y);
      }
    }
    font.setColor(Color.WHITE);
    batch.end();
  }

  private void drawEdges() {
    HdpiUtils.glViewport(
        264,
        122,
        Math.max(1, Gdx.graphics.getWidth() - 564),
        Math.max(1, Gdx.graphics.getHeight() - 202));
    lines.setProjectionMatrix(city.camera.combined);
    lines.begin(ShapeRenderer.ShapeType.Line);
    lines.setColor(CYAN);
    Map<String, CityRenderer.Building> map = new HashMap<>();
    for (var b : city.buildings()) map.put(b.file.path(), b);
    int count = 0;
    for (var e : edges) {
      if (!e.source().equals(selected) && !e.target().equals(selected)) continue;
      var a = map.get(e.source());
      var b = map.get(e.target());
      if (a == null || b == null) continue;
      if (++count > 200) break;
      Vector3 start = new Vector3(), end = new Vector3();
      a.bounds.getCenter(start);
      start.y = a.bounds.max.y;
      b.bounds.getCenter(end);
      end.y = b.bounds.max.y;
      Vector3 prev = start.cpy();
      for (int i = 1; i <= 20; i++) {
        float t = i / 20f;
        Vector3 next = new Vector3(start).lerp(end, t);
        next.y += MathUtils.sin(t * MathUtils.PI) * 40;
        lines.line(prev, next);
        prev = next;
      }
    }
    lines.end();
    HdpiUtils.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
  }

  @Override
  public void resize(int w, int h) {
    if (stage == null) return;
    stage.getViewport().update(w, h, true);
    top.setBounds(0, h - 80, w, 80);
    side.setBounds(0, 122, 264, h - 202);
    inspectPanel.setBounds(w - 300, 122, 300, h - 202);
    bottom.setBounds(0, 0, w, 122);
    header.setPosition(290, h - 123);
    metrics.setPosition(290, h - 149);
    diagnostics.setPosition(290, 145);
  }

  @Override
  public void dispose() {
    generation.incrementAndGet();
    worker.shutdownNow();
    stage.dispose();
    skin.dispose();
    city.close();
    comparisonCity.close();
    lines.dispose();
    if (store != null)
      try {
        store.close();
      } catch (Exception ignored) {
      }
  }
}
