# Consumer maps and navigation policy

Consumer mods can replace the system map and cosmic atlas with their own Minecraft
`Screen`. AstraEngine supplies immutable celestial/navigation snapshots and queues
navigation requests; the server still owns discovery, visits, route acceptance and
movement. No SolarTech classes, economy or construction data are required.

Register a **physical-client-only**, client-thread listener on the NeoForge game bus:

```java
NeoForge.EVENT_BUS.addListener((NavigationMapOpeningEvent event) -> {
    event.setScreen(new MyNavigationScreen(event.view(), event.access()));
});
```

The types live in `dev.lexawhatt.astraengine.client.map`. The opening event runs for
the M binding, `/astra-flight map`, `/astra-flight atlas`, and the default screens'
links. `defaultScreen()` exposes the original screen; leaving the event unchanged
retains it. Ordinary NeoForge screen events can add widgets to default screens.
Use event priority to coordinate multiple consumers; later listeners see prior
replacements. Do not recursively open another map inside an opening listener.

`NavigationMapAccess.snapshot()` returns an optional `NavigationMapSnapshot`:
current system, charted descriptors, visits, virtual position in meters, orientation,
orbital seconds, route status, selected body and synchronized navigation policy.
`canStartRoute` also accounts for camera catch-up after arrival; zero remaining
server ticks alone does not mean the view has finished settling.
Collections and descriptors are immutable. Empty means not synchronized or no
longer connected. Do not retain a snapshot expecting it to update in place.

`request(action, target)` supports selecting/approaching a body, aiming/jumping to
a system, charting a public atlas anchor, scanning and cancellation. Scan and cancel
use an empty target. A true return means **queued locally**, not server acceptance;
observe subsequent snapshots for authoritative progress. Invalid/stale requests
return false. Null inputs throw. `open(view)` switches maps through the same event.
Use canonical descriptor IDs; missing custom systems are never generated as fallbacks.

All handle calls require the client thread. Resource reload retains the handle and
policy. Logout invalidates that connection's handles permanently, even after a
later reconnect. Screens own their widgets and any resources they allocate; the
access handle does not transfer ownership of engine renderers or world objects.

## Operator cheats

```text
/gamerule astraFreeNavigation true
/gamerule astraTravelSeconds 1
```

`astraFreeNavigation` defaults to false. When true, valid built-in destinations can
be jumped to without prior discovery or a visit; registered custom destinations
still need their actual definition. Enabling the rule itself reveals nothing.
An accepted jump charts its destination, and arrival records the visit. Canceling
does not record a visit. The atlas has a Jump button; `/astra-flight jump <systemId>`
also submits a server-validated request for a destination not listed on the map.

`astraTravelSeconds` accepts 0..3600. Zero keeps the ordinary approach planner and
80-tick interstellar jump. Positive values fix both local body approach and system
jumps to `seconds * 20` server ticks, captured when accepted. Thus 1 means 20 ticks
(one real second at 20 TPS). Changing the rule does not retime an accepted route.
Local collision checks remain active; an obstructed route can still be rejected.
Surface chunk preparation and landing are not accelerated by this rule.

Both rules use Minecraft's operator permissions and saved gamerule lifecycle.
Client screens cannot grant themselves permission. Policy synchronizes on login
and changes; safe defaults apply before synchronization. Navigation payload wire
version is 9; saved exploration data retains its existing format. Update both
client and server together. B or `CANCEL_ROUTE` cancels a pending system jump at
its source, or a local approach at the reached position.
