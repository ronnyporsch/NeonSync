@file:Suppress("FunctionName")

package neonsync

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory

// ---- org.kde.StatusNotifierItem ---------------------------------------------------------------

@JvmSuppressWildcards
class Pixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    /** ARGB32, network byte order. */
    @field:Position(2) val data: ByteArray,
) : Struct()

@JvmSuppressWildcards
class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val pixmaps: List<Pixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val text: String,
) : Struct()

@DBusInterfaceName("org.kde.StatusNotifierItem")
@JvmSuppressWildcards
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)
    class NewToolTip(path: String) : DBusSignal(path)
    class NewTitle(path: String) : DBusSignal(path)
    class NewStatus(path: String, val status: String) : DBusSignal(path, status)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

// ---- com.canonical.dbusmenu -------------------------------------------------------------------

@JvmSuppressWildcards
class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

@JvmSuppressWildcards
class MenuItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

@JvmSuppressWildcards
class MenuEvent(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

/**
 * Generic on purpose: dbus-java derives the signature of non-generic tuples from the raw field
 * types, which loses the element type of lists. Type arguments are read from the method signature.
 */
class TwoTuple<A, B>(
    @field:Position(0) val first: A,
    @field:Position(1) val second: B,
) : Tuple()

@DBusInterfaceName("com.canonical.dbusmenu")
@JvmSuppressWildcards
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): TwoTuple<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): TwoTuple<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

// ---- Implementation ---------------------------------------------------------------------------

/**
 * Hand-written `org.freedesktop.DBus.Properties`: dbus-java's `@DBusBoundProperty` replies to `Get`
 * with the bare value instead of a variant, which breaks clients. Variants are built with explicit
 * signatures because dbus-java can't infer them for structs containing lists.
 */
abstract class PropertySource : Properties {
    protected abstract val properties: Map<String, () -> Variant<*>>

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (properties[propertyName] ?: throw UnknownPropertyException(propertyName))() as A

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties.mapValues { it.value() }

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) =
        throw org.freedesktop.dbus.exceptions.DBusExecutionException("Property $propertyName is read-only")
}

class UnknownPropertyException(name: String) : org.freedesktop.dbus.exceptions.DBusExecutionException("Unknown property $name")

/** A flat-ish menu model; items with children become submenus. */
class MenuItem(
    val label: String = "",
    val separator: Boolean = false,
    val enabled: Boolean = true,
    /** null = no toggle, else (radio?, checked) */
    val toggle: Pair<Boolean, Boolean>? = null,
    val iconName: String? = null,
    val children: List<MenuItem> = emptyList(),
    val onClick: (() -> Unit)? = null,
)

data class TrayState(
    val active: Boolean,
    val tooltipTitle: String,
    val tooltipText: String,
)

/**
 * KDE system tray icon via the StatusNotifierItem D-Bus protocol. AWT's SystemTray relies on
 * XEmbed which does not work on Wayland, SNI is what Plasma natively uses.
 */
class Tray(
    private val bus: DBusConnection,
    private val menuProvider: () -> List<MenuItem>,
    private val onActivate: () -> Unit,
    private val onScroll: (Int) -> Unit,
) {
    private val log = LoggerFactory.getLogger(Tray::class.java)
    private val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"

    @Volatile
    private var state = TrayState(false, "NeonSync", "")

    /** The logo; static, so it is rendered once. */
    private val pixmaps: List<Pixmap> by lazy { ICON_SIZES.map { Pixmap(it, it, Logo.argb(it)) } }

    private val item = Item()
    private val menu = Menu()
    private var watcherHandler: AutoCloseable? = null

    fun start() {
        bus.requestBusName(busName)
        bus.exportObject(ITEM_PATH, item)
        // Built here rather than in the constructor: the menu provider may depend on state of the owner
        menu.changed()
        bus.exportObject(MENU_PATH, menu)
        watcherHandler = bus.addSigHandler(DBus.NameOwnerChanged::class.java) { sig ->
            // Plasma restarted: register again
            if (sig.name == WATCHER && sig.newOwner.isNotEmpty()) register()
        }
        register()
    }

    private fun register() {
        try {
            bus.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
                .RegisterStatusNotifierItem(busName)
            log.debug("Registered tray icon as {}", busName)
        } catch (e: Exception) {
            log.warn("Could not register tray icon (is a StatusNotifierWatcher running?): {}", e.message)
        }
    }

    fun update(newState: TrayState) {
        val old = state
        state = newState
        if (old.tooltipText != newState.tooltipText || old.tooltipTitle != newState.tooltipTitle) {
            emit(StatusNotifierItem.NewToolTip(ITEM_PATH))
        }
        if (old.active != newState.active) {
            emit(StatusNotifierItem.NewStatus(ITEM_PATH, status()))
        }
    }

    /** Call when the menu model changed. */
    fun menuChanged() = menu.changed()

    fun stop() {
        watcherHandler?.close()
        runCatching { bus.unExportObject(ITEM_PATH) }
        runCatching { bus.unExportObject(MENU_PATH) }
        runCatching { bus.releaseBusName(busName) }
    }

    private fun emit(signal: DBusSignal) = runCatching { bus.sendMessage(signal) }

    private fun status() = if (state.active) "Active" else "Passive"

    private inner class Item : StatusNotifierItem, PropertySource() {
        override val properties: Map<String, () -> Variant<*>> = mapOf(
            "Category" to { Variant("Hardware") },
            "Id" to { Variant("neonsync") },
            "Title" to { Variant("NeonSync") },
            "Status" to { Variant(status()) },
            "WindowId" to { Variant(0) },
            "IconName" to { Variant("") },
            "IconPixmap" to { Variant(pixmaps, PIXMAPS_SIGNATURE) },
            "OverlayIconName" to { Variant("") },
            "AttentionIconName" to { Variant("") },
            "ToolTip" to { Variant(ToolTip("", pixmaps, state.tooltipTitle, state.tooltipText), TOOLTIP_SIGNATURE) },
            "ItemIsMenu" to { Variant(false) },
            "Menu" to { Variant(DBusPath(MENU_PATH)) },
        )

        override fun getObjectPath() = ITEM_PATH
        override fun Activate(x: Int, y: Int) = onActivate()
        override fun SecondaryActivate(x: Int, y: Int) = onActivate()
        override fun ContextMenu(x: Int, y: Int) {}
        override fun Scroll(delta: Int, orientation: String) {
            if (orientation.equals("vertical", ignoreCase = true)) onScroll(delta)
        }
    }

    private inner class Menu : DBusMenu, PropertySource() {
        override val properties: Map<String, () -> Variant<*>> = mapOf(
            "Version" to { Variant(UInt32(3)) },
            "TextDirection" to { Variant("ltr") },
            "Status" to { Variant("normal") },
            "IconThemePath" to { Variant(emptyList<String>(), "as") },
        )
        private var revision = 1L
        private var items: Map<Int, MenuItem> = emptyMap()
        private var root: MenuLayout = MenuLayout(0, emptyMap(), emptyList())

        @Synchronized
        fun changed() {
            rebuild()
            revision++
            emit(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), 0))
        }

        @Synchronized
        private fun rebuild() {
            val map = HashMap<Int, MenuItem>()
            var nextId = 1
            fun build(item: MenuItem): MenuLayout {
                val id = nextId++
                map[id] = item
                return MenuLayout(id, props(item), item.children.map { Variant(build(it), LAYOUT_SIGNATURE) })
            }
            val children = menuProvider().map { Variant(build(it), LAYOUT_SIGNATURE) }
            root = MenuLayout(0, mapOf("children-display" to Variant("submenu")), children)
            items = map
        }

        private fun props(item: MenuItem): Map<String, Variant<*>> = buildMap {
            if (item.separator) {
                put("type", Variant("separator"))
                return@buildMap
            }
            put("label", Variant(item.label))
            put("enabled", Variant(item.enabled))
            item.iconName?.let { put("icon-name", Variant(it)) }
            item.toggle?.let { (radio, checked) ->
                put("toggle-type", Variant(if (radio) "radio" else "checkmark"))
                put("toggle-state", Variant(if (checked) 1 else 0))
            }
            if (item.children.isNotEmpty()) put("children-display", Variant("submenu"))
        }

        private fun find(layout: MenuLayout, id: Int): MenuLayout? {
            if (layout.id == id) return layout
            for (child in layout.children) find(child.value as MenuLayout, id)?.let { return it }
            return null
        }

        private fun limitDepth(layout: MenuLayout, depth: Int): MenuLayout = when {
            depth == 0 -> MenuLayout(layout.id, layout.properties, emptyList())
            else -> MenuLayout(
                layout.id, layout.properties,
                layout.children.map { Variant(limitDepth(it.value as MenuLayout, depth - 1), LAYOUT_SIGNATURE) },
            )
        }

        override fun getObjectPath() = MENU_PATH

        @Synchronized
        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): TwoTuple<UInt32, MenuLayout> {
            val node = find(root, parentId) ?: root
            return TwoTuple(UInt32(revision), if (recursionDepth < 0) node else limitDepth(node, recursionDepth))
        }

        @Synchronized
        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> {
            val wanted = if (ids.isEmpty()) items.keys.toList() else ids
            return wanted.mapNotNull { id ->
                val props = (if (id == 0) root.properties else items[id]?.let(::props)) ?: return@mapNotNull null
                MenuItemProperties(id, if (propertyNames.isEmpty()) props else props.filterKeys { it in propertyNames })
            }
        }

        @Synchronized
        override fun GetProperty(id: Int, name: String): Variant<*> =
            items[id]?.let(::props)?.get(name) ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            val item = synchronized(this) { items[id] } ?: return
            // Run outside the D-Bus dispatch thread; actions may do D-Bus calls themselves.
            Thread.ofVirtual().start {
                try {
                    item.onClick?.invoke()
                } catch (e: Exception) {
                    log.warn("Menu action failed", e)
                }
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int) = false
        override fun AboutToShowGroup(ids: List<Int>) = TwoTuple<List<Int>, List<Int>>(emptyList(), emptyList())
    }

    companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        private const val LAYOUT_SIGNATURE = "(ia{sv}av)"
        private const val PIXMAPS_SIGNATURE = "a(iiay)"
        private const val TOOLTIP_SIGNATURE = "(sa(iiay)ss)"

        /** Plasma picks the closest size; the larger ones keep the icon sharp on HiDPI panels. */
        private val ICON_SIZES = listOf(16, 22, 24, 32, 48, 64, 128)
    }
}
