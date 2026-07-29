package dev.customplayernametags.manager;

import dev.customplayernametags.CustomPlayerNametags;
import dev.customplayernametags.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invisible-body marker {@link TextDisplay} entities that carry the full
 * colored nametag text for every player. Vanilla overhead nametags are
 * suppressed via team {@code NameTagVisibility.NEVER}.
 *
 * <p>A {@code TextDisplay} is used instead of an {@code ArmorStand} for two
 * reasons:
 * <ul>
 *   <li><b>Rock-solid tracking on both platforms:</b> the tag is
 *       re-teleported to the player's exact current position every single
 *       tick (the same rate the server itself receives player movement).
 *       {@link Display#setTeleportDuration(int)} controls how many ticks
 *       the client eases each of those updates over instead of snapping
 *       straight to it, and the right value is different per platform:
 *       <ul>
 *         <li>Java clients render each per-tick update in lockstep with the
 *             server tick, so a duration of {@code 0} (hard snap) keeps the
 *             tag pinned exactly above the head with zero drift. Any
 *             nonzero duration here re-arms a new easing animation on top
 *             of the still-in-progress previous one every tick, which
 *             during fast/erratic movement (sprinting, direction changes,
 *             knockback, elytra) makes the tag perpetually lag behind and
 *             visibly drift/swim instead of tracking the player.</li>
 *         <li>Bedrock clients receive these updates through Geyser's
 *             translation layer, where per-tick packets don't land on as
 *             clean a cadence as native Java ticks, so a hard snap there
 *             instead reads as jittery. Geyser does respect this metadata
 *             value (it doesn't unconditionally smooth on its own), so
 *             keeping a short {@link #BEDROCK_TELEPORT_DURATION_TICKS}
 *             interpolation window is what keeps Bedrock's tag looking
 *             steady.</li>
 *       </ul></li>
 *   <li><b>No invisible-flag flash:</b> An invisible {@code ArmorStand} has
 *       a body model that is hidden via a separate "Invisible" metadata
 *       flag sent after the entity's spawn packet. When re-shown to a
 *       viewer, there is a brief window where the client has the spawn
 *       packet but not yet the invisibility metadata, so the stand's model
 *       flashes visible for a frame. A {@code TextDisplay} has no body
 *       model at all (it only ever renders its text), so there is nothing
 *       to flash regardless of packet ordering.</li>
 * </ul>
 *
 * Visibility (Paper API):
 * <ul>
 *   <li>{@code setVisibleByDefault(false)}</li>
 *   <li>{@code showEntity} for every online player except the owner
 *       (sneaking dims the tag rather than hiding it — see below)</li>
 *   <li>Owner never sees their own tag (cannot see own nametag when looking up)</li>
 * </ul>
 *
 * Standing vs. sneaking look:
 * <ul>
 *   <li><b>Standing:</b> full-brightness text, {@code setSeeThrough(true)}.
 *       Visible through walls (vanilla-style).</li>
 *   <li><b>Sneaking:</b> text colors darkened (component rewrite), reduced
 *       opacity, and per-viewer line-of-sight checks so the tag stays visible
 *       in the open but is hidden when a wall blocks the view. Works for both
 *       Java and Bedrock viewers without respawning the entity (respawn caused
 *       a visible jump on Java).</li>
 * </ul>
 * <p>Nametag height tracks eye height so the gap above the head stays constant
 * when crouching. On uncrouch, see-through is delayed one tick until the tag
 * is already at standing height, preventing a through-wall jump. That delayed
 * hide/respawn dance is applied <em>only</em> to Bedrock viewers who were
 * actually LOS-hidden during the sneak — respawning a viewer who already had
 * a clear view produces the same "spawns high, glides down" glitch it exists
 * to prevent, so already-visible viewers just get a plain in-place teleport.
 */
public final class NametagDisplayManager {

    /** How many ticks a position change should interpolate over on Bedrock (via Geyser). */
    private static final int BEDROCK_TELEPORT_DURATION_TICKS = 3;

    /**
     * Bedrock/Geyser renders TextDisplay slightly higher than Java at the same
     * world Y — pull Bedrock tags down so both platforms match visually.
     */
    private static final double BEDROCK_HEIGHT_ADJUST = -0.10;

    /**
     * Vanilla standing / sneaking eye heights. Fixed values (not live
     * {@code getEyeHeight()}) so stand↔crouch snaps to the final pose in one
     * teleport instead of tracking intermediate eye heights over several ticks
     * (which reads as a glide on Java).
     */
    private static final double STANDING_EYE_HEIGHT = 1.62;
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    /**
     * How many ticks the nametag takes to rise from crouch height to standing
     * height on uncrouch, for the Bedrock viewers who are seeing it for the
     * first time in a while (was LOS-hidden behind a wall). Short and snappy,
     * but gives the client real per-tick position updates to track instead of
     * ever spawning the entity directly at its final height.
     */
    private static final int UNCROUCH_RISE_TICKS = 4;


    /**
     * Fully opaque text. Use 255 (not the API's -1 sentinel) so Geyser/Bedrock
     * also renders solid text while standing — Geyser has been observed to
     * leave tags semi-transparent when opacity is left at -1.
     */
    private static final byte OPACITY_STANDING = (byte) 255;

    /**
     * Dimmed text opacity while sneaking (~40% alpha). Combined with
     * {@link #dim} so Java and Bedrock get the same dull crouch look.
     */
    private static final byte OPACITY_SNEAKING = (byte) 100;

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;

    private NametagManager nametagManager;

    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastSneaking = new ConcurrentHashMap<>();
    /** In-flight uncrouch rise animations (see {@link #beginUncrouchRise}), keyed by owner. */
    private final Map<UUID, BukkitTask> uncrouchRiseTasks = new ConcurrentHashMap<>();
    /** Last full-brightness nametag text per player (used to rebuild a dimmed copy while sneaking). */
    private final Map<UUID, Component> brightTexts = new ConcurrentHashMap<>();
    private BukkitTask followTask;

    public NametagDisplayManager(CustomPlayerNametags plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setNametagManager(NametagManager nametagManager) {
        this.nametagManager = nametagManager;
    }

    public void start() {
        stopFollowTask();
        this.followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFollow, 1L, 1L);
    }

    public void shutdown() {
        stopFollowTask();
        for (UUID uuid : displays.keySet().toArray(new UUID[0])) {
            remove(uuid);
        }
        displays.clear();
        lastSneaking.clear();
        brightTexts.clear();
        for (BukkitTask task : uncrouchRiseTasks.values()) {
            task.cancel();
        }
        uncrouchRiseTasks.clear();
    }

    private void stopFollowTask() {
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
    }

    public void update(Player target, String fullLegacyText) {
        if (!target.isOnline()) {
            remove(target.getUniqueId());
            return;
        }

        Component nameComponent = LegacyComponentSerializer.legacySection().deserialize(
                fullLegacyText == null ? "" : fullLegacyText);
        brightTexts.put(target.getUniqueId(), nameComponent);

        TextDisplay display = displays.get(target.getUniqueId());
        if (display == null || !display.isValid() || display.getWorld() != target.getWorld()) {
            if (display != null) {
                remove(target.getUniqueId());
            }
            display = spawn(target, nameComponent);
            if (display == null) {
                return;
            }
            displays.put(target.getUniqueId(), display);
        } else {
            applyAppearance(display);
            boolean sneaking = target.isSneaking();
            Boolean previous = lastSneaking.put(target.getUniqueId(), sneaking);
            if (previous == null || previous != sneaking) {
                // Sneak flipped — must go through the full transition (respawn
                // for Java see_through, LOS-aware Bedrock reveal, etc).
                applySneakState(target, display, sneaking);
            } else {
                // Sneak state unchanged: only refresh the text/opacity. Do NOT
                // call applySneakState() here — it re-runs the full hide/
                // teleport/delayed-reveal dance every time, which this method
                // is invoked from periodically (once a second, regardless of
                // whether anything actually changed). Calling it unconditionally
                // caused the tag to visibly hide and respawn every second for
                // Bedrock viewers watching through a wall, even though nothing
                // about the pose or visibility needed to change.
                refreshTextOnly(target, display, sneaking);
            }
        }

        teleportAbove(target, display);
    }

    public void remove(UUID uuid) {
        TextDisplay display = displays.remove(uuid);
        lastSneaking.remove(uuid);
        brightTexts.remove(uuid);
        BukkitTask riseTask = uncrouchRiseTasks.remove(uuid);
        if (riseTask != null) {
            riseTask.cancel();
        }
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /** Shows every existing display to a viewer except their own (see class javadoc for sneak visibility rules). */
    public void showExistingTo(Player viewer) {
        for (Map.Entry<UUID, TextDisplay> entry : displays.entrySet()) {
            if (entry.getKey().equals(viewer.getUniqueId())) {
                continue;
            }
            TextDisplay display = entry.getValue();
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (display == null || !display.isValid() || owner == null) {
                continue;
            }
            boolean sneaking = owner.isSneaking();
            if (sneaking
                    && BedrockDetector.isBedrockPlayer(viewer)
                    && !hasLineOfSight(viewer, display.getLocation())) {
                viewer.hideEntity(plugin, display);
            } else {
                viewer.showEntity(plugin, display);
            }
        }
    }

    private TextDisplay spawn(Player owner, Component name) {
        brightTexts.put(owner.getUniqueId(), name);
        boolean sneaking = owner.isSneaking();
        lastSneaking.put(owner.getUniqueId(), sneaking);
        TextDisplay display = createDisplay(owner, name, locationAbove(owner), sneaking);
        if (display != null) {
            applyViewerVisibility(owner, display, sneaking);
        }
        return display;
    }

    /**
     * Creates a TextDisplay with all nametag properties, including the
     * current sneak see-through / opacity / dimmed text, applied inside the
     * spawn consumer so they land on the initial metadata packet.
     */
    private TextDisplay createDisplay(Player owner, Component bright, Location loc, boolean sneaking) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        final boolean bedrock = isBedrockOwner(owner);
        final Component displayText = sneaking ? dim(bright) : bright;

        return world.spawn(loc, TextDisplay.class, entity -> {
            entity.text(displayText);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setLineWidth(200);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setSilent(true);
            entity.setVisibleByDefault(false);
            entity.setShadowed(false);
            entity.setDefaultBackground(true);
            entity.setTeleportDuration(bedrock ? BEDROCK_TELEPORT_DURATION_TICKS : 0);
            // Must be set here (pre-track) so Java clients receive see_through
            // on the spawn metadata packet. Later in-place changes are ignored.
            entity.setSeeThrough(!sneaking);
            entity.setTextOpacity(sneaking ? OPACITY_SNEAKING : OPACITY_STANDING);
        });
    }

    /**
     * Updates only the displayed text/opacity for the current sneak state,
     * without touching position or per-viewer visibility. Used for periodic
     * refreshes (e.g. placeholder text changes) where the sneak state itself
     * hasn't changed, so none of the hide/teleport/delayed-reveal machinery
     * in {@link #applySneakState} needs to run again.
     */
    private void refreshTextOnly(Player owner, TextDisplay display, boolean sneaking) {
        Component bright = brightTexts.get(owner.getUniqueId());
        if (bright == null) {
            bright = display.text();
            brightTexts.put(owner.getUniqueId(), bright);
        }
        display.text(sneaking ? dim(bright) : bright);
        display.setTextOpacity(sneaking ? OPACITY_SNEAKING : OPACITY_STANDING);
    }

    /**
     * Applies standing-vs-sneaking appearance on the custom TextDisplay.
     * Always keeps the configured format (never falls back to the raw username).
     *
     * <p>Crouch: grey text + reduced opacity + LOS occlusion for every viewer.
     * Standing: full-colour text + full opacity + see-through.
     *
     * <p>Teleport is applied <em>before</em> showEntity so viewers who regain
     * LOS (or uncrouch visibility) never see the tag pop in at a stale height.
     */
    private void applySneakState(Player owner, TextDisplay display, boolean sneaking) {
        Component bright = brightTexts.get(owner.getUniqueId());
        if (bright == null) {
            bright = display.text();
            brightTexts.put(owner.getUniqueId(), bright);
        }

        // Ensure vanilla nametag stays hidden (custom TextDisplay is the only tag).
        if (nametagManager != null) {
            nametagManager.setVanillaNametagHidden(owner, true);
        }

        display.text(sneaking ? dim(bright) : bright);
        display.setTextOpacity(sneaking ? OPACITY_SNEAKING : OPACITY_STANDING);

        if (sneaking) {
            BukkitTask riseTask = uncrouchRiseTasks.remove(owner.getUniqueId());
            if (riseTask != null) {
                riseTask.cancel();
            }
            display.setSeeThrough(false);
            snapTeleportAbove(owner, display);
            applyViewerVisibility(owner, display, true);
        } else {
            // Uncrouch.
            //
            // Only Bedrock viewers who were ACTUALLY LOS-hidden during the
            // sneak (e.g. watching through a wall) need special handling.
            // Bedrock viewers who already had a clear line of sight (the tag
            // was never hidden from them) get a plain in-place teleport, same
            // as Java viewers always do — no hide/respawn needed at all.
            Location preUncrouchLoc = display.getLocation();
            List<UUID> needsReveal = new ArrayList<>();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                    continue;
                }
                if (BedrockDetector.isBedrockPlayer(viewer)
                        && !hasLineOfSight(viewer, preUncrouchLoc)) {
                    needsReveal.add(viewer.getUniqueId());
                }
            }

            showToJavaViewers(owner, display);

            if (needsReveal.isEmpty()) {
                // Nobody was hidden — simple instant snap to standing height.
                display.setSeeThrough(false);
                snapTeleportAbove(owner, display);
                display.setSeeThrough(true);
                applyViewerVisibility(owner, display, false);
                return;
            }

            // Some Bedrock viewers currently can't see the tag at all (wall
            // blocked it while sneaking). Spawning the entity for them
            // directly at the final standing height is what causes Geyser's
            // spawn-then-overshoot glitch ("appears high, glides down"),
            // regardless of how carefully the timing of that spawn is done.
            //
            // Instead: reveal it to them right where it already sits — the
            // same crouch-height position it would already be at if it had
            // been visible to them this whole time — then animate it rising
            // to standing height over a few ticks, same as normal movement
            // tracking. No hide/respawn ever happens once it's shown.
            display.setSeeThrough(false);
            beginUncrouchRise(owner, display, needsReveal);
        }
    }

    /**
     * Reveals the nametag to {@code newlyVisibleViewers} at its current
     * (crouch-height) position, then eases it up to standing height over
     * {@link #UNCROUCH_RISE_TICKS} ticks with a single client-interpolated
     * teleport (rather than repeated per-tick hard snaps, which read as a
     * choppy bobble instead of a smooth rise — the same reason normal
     * Bedrock movement tracking elsewhere uses a short interpolation window
     * instead of duration 0 every tick). Used only for the Bedrock viewers
     * who were LOS-hidden while the owner was sneaking — see the uncrouch
     * branch of {@link #applySneakState}.
     */
    private void beginUncrouchRise(Player owner, TextDisplay display, List<UUID> newlyVisibleViewers) {
        final UUID ownerId = owner.getUniqueId();

        BukkitTask existing = uncrouchRiseTasks.remove(ownerId);
        if (existing != null) {
            existing.cancel();
        }

        // Reveal at the current (crouch-height) position first — the spawn
        // packet lands there, unchanged, so there's nothing to glitch.
        for (UUID vid : newlyVisibleViewers) {
            Player v = Bukkit.getPlayer(vid);
            if (v != null) {
                v.showEntity(plugin, display);
            }
        }

        // One teleport to the final standing position, with a client-side
        // interpolation window so Java and Bedrock both ease smoothly
        // between the two heights instead of us hard-snapping every tick.
        display.setInterpolationDuration(0);
        display.setInterpolationDelay(0);
        display.setTeleportDuration(UNCROUCH_RISE_TICKS);
        display.teleport(locationAboveAt(owner, 1.0));

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            uncrouchRiseTasks.remove(ownerId);
            TextDisplay s = displays.get(ownerId);
            Player p = Bukkit.getPlayer(ownerId);
            if (s == null || !s.isValid() || p == null || !p.isOnline() || p.isSneaking()) {
                // Aborted (re-crouched, disconnected, etc) — sneaking's own
                // snap/LOS handling already takes over in that case.
                return;
            }
            // Rise finished: restore the normal per-platform teleport
            // duration for regular movement tracking, and finalize the
            // standing look.
            s.setTeleportDuration(isBedrockOwner(p) ? BEDROCK_TELEPORT_DURATION_TICKS : 0);
            s.setSeeThrough(true);
            applyViewerVisibility(p, s, false);
        }, UNCROUCH_RISE_TICKS);
        uncrouchRiseTasks.put(ownerId, task);
    }

    private void showToJavaViewers(Player owner, TextDisplay display) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                viewer.hideEntity(plugin, display);
            } else if (!BedrockDetector.isBedrockPlayer(viewer)) {
                viewer.showEntity(plugin, display);
            }
        }
    }

    /**
     * Per-viewer visibility for a nametag display.
     *
     * <ul>
     *   <li>Owner never sees their own tag.</li>
     *   <li>Java viewers: always shown. Occlusion while sneaking is handled
     *       purely by {@code seeThrough=false} on the TextDisplay — do
     *       <em>not</em> hideEntity, or the client re-spawns the entity on
     *       uncrouch and the tag jumps (especially when viewed through a
     *       wall).</li>
     *   <li>Bedrock viewers: Geyser ignores {@code see_through}, so while the
     *       owner is sneaking we LOS-hide the tag when blocked by blocks.</li>
     * </ul>
     */
    private void applyViewerVisibility(Player owner, TextDisplay display, boolean sneaking) {
        Location tagLoc = display.getLocation();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                viewer.hideEntity(plugin, display);
                continue;
            }
            if (sneaking
                    && BedrockDetector.isBedrockPlayer(viewer)
                    && !hasLineOfSight(viewer, tagLoc)) {
                viewer.hideEntity(plugin, display);
            } else {
                viewer.showEntity(plugin, display);
            }
        }
    }

    /**
     * True if no solid block sits between the viewer's eyes and {@code target}.
     * Used only for Bedrock viewers while the owner is sneaking (Geyser does
     * not honor TextDisplay see_through).
     */
    private static boolean hasLineOfSight(Player viewer, Location target) {
        Location eye = viewer.getEyeLocation();
        if (eye.getWorld() != target.getWorld()) {
            return false;
        }
        double distance = eye.distance(target);
        if (distance < 0.01) {
            return true;
        }
        Vector direction = target.toVector().subtract(eye.toVector()).normalize();
        // Slightly short of the tag so the ray ends just before the display.
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye,
                direction,
                distance - 0.05,
                FluidCollisionMode.NEVER,
                true);
        return hit == null;
    }

    /**
     * Returns a darkened copy of {@code in} so the nametag looks faint while
     * sneaking. Rewriting colors is what makes the dim visible on Java
     * clients (they ignore {@code text_opacity} updates on these Displays).
     */
    /** Solid grey used for the crouch nametag on every platform. */
    private static final TextColor SNEAK_GREY = TextColor.color(160, 160, 160);

    /**
     * Forces the whole component tree to {@link #SNEAK_GREY} so Java and
     * Bedrock share the same dull crouch colour (rank/name colours are dropped
     * while sneaking, matching vanilla's greyed nametag).
     */
    private static Component dim(Component in) {
        Style style = in.style();
        Component out = in.style(style.toBuilder().color(SNEAK_GREY).build());
        List<Component> children = in.children();
        if (!children.isEmpty()) {
            List<Component> dimmed = new ArrayList<>(children.size());
            for (Component child : children) {
                dimmed.add(dim(child));
            }
            out = out.children(dimmed);
        }
        return out;
    }

    /**
     * Applies the (fixed, non-configurable) nametag look: no drop shadow,
     * and always the standard translucent grey/black background box. Cheap
     * to call repeatedly (plain metadata setters), so it's re-applied on
     * every periodic refresh rather than only at spawn time — that way
     * {@code /nametags reload} takes effect on existing tags within one
     * refresh interval instead of requiring a respawn/rejoin.
     */
    private void applyAppearance(TextDisplay display) {
        display.setShadowed(false);
        display.setDefaultBackground(true);
    }

    private void tickFollow() {
        for (Map.Entry<UUID, TextDisplay> entry : displays.entrySet()) {
            UUID uuid = entry.getKey();
            TextDisplay display = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                remove(uuid);
                continue;
            }
            if (display == null || !display.isValid()) {
                displays.remove(uuid);
                continue;
            }
            if (display.getWorld() != player.getWorld()) {
                remove(uuid);
                continue;
            }

            boolean sneaking = player.isSneaking();
            Boolean previous = lastSneaking.put(uuid, sneaking);
            if (previous == null || previous != sneaking) {
                applySneakState(player, display, sneaking);
            } else if (sneaking) {
                // Refresh Bedrock LOS only; Java keeps the entity tracked.
                applyViewerVisibility(player, display, true);
            }

            teleportAbove(player, display);
        }
    }

    private void teleportAbove(Player player, TextDisplay display) {
        if (uncrouchRiseTasks.containsKey(player.getUniqueId())) {
            // beginUncrouchRise() is driving position for this owner right
            // now — don't fight it with an immediate snap to the final height.
            return;
        }
        Location dest = locationAbove(player);
        Location cur = display.getLocation();
        if (cur.getWorld() == dest.getWorld() && cur.distanceSquared(dest) < 0.0001) {
            return;
        }
        // Movement (walk/jump/fall): platform duration only.
        // Pose snaps are handled exclusively by snapTeleportAbove (duration 0).
        // Using duration 0 on large jump Y deltas made Bedrock tags jitter.
        display.setTeleportDuration(
                isBedrockOwner(player) ? BEDROCK_TELEPORT_DURATION_TICKS : 0);
        display.teleport(dest);
    }

    private Location locationAbove(Player player) {
        return locationAboveAt(player, player.isSneaking() ? 0.0 : 1.0);
    }

    /**
     * Same as {@link #locationAbove}, but with the crouch↔stand eye-height
     * blend explicit as {@code t} (0 = full crouch, 1 = full standing)
     * instead of derived from {@code player.isSneaking()}. Used by
     * {@link #beginUncrouchRise} to animate intermediate heights.
     */
    private Location locationAboveAt(Player player, double t) {
        // Constant gap above the head: config offset is "from feet standing",
        // convert to gap-above-eyes, then apply the pose's (possibly blended) eye height.
        double aboveEyes = config.getNametagHeightOffset() - STANDING_EYE_HEIGHT;
        double eye = SNEAK_EYE_HEIGHT + t * (STANDING_EYE_HEIGHT - SNEAK_EYE_HEIGHT);
        double offset = eye + aboveEyes;
        if (isBedrockOwner(player)) {
            offset += BEDROCK_HEIGHT_ADJUST;
        }
        return player.getLocation().add(0.0, offset, 0.0);
    }

    /**
     * Teleports the display with {@code teleportDuration 0} so the stand↔crouch
     * height change snaps instantly (no client-side easing), then restores the
     * platform-appropriate duration for normal movement tracking.
     */
    private void snapTeleportAbove(Player player, TextDisplay display) {
        // Pose change only — hard snap on every client. Also clear Display
        // transformation interpolation so Java cannot ease the move.
        display.setTeleportDuration(0);
        display.setInterpolationDuration(0);
        display.setInterpolationDelay(0);
        display.teleport(locationAbove(player));
        // Restore Bedrock movement smoothing next tick (not mid-snap).
        if (isBedrockOwner(player)) {
            final UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                TextDisplay still = displays.get(id);
                if (still != null && still.isValid()) {
                    still.setTeleportDuration(BEDROCK_TELEPORT_DURATION_TICKS);
                }
            });
        }
    }

    private boolean isBedrockOwner(Player player) {
        return BedrockDetector.isBedrockPlayer(player);
    }
}
