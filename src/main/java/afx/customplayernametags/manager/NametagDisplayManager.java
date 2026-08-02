package afx.customplayernametags.manager;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>No invisible-flag flash:</b> An invisible {@code ArmorStand} has
 *       a body model that is hidden via a separate "Invisible" metadata
 *       flag sent after the entity's spawn packet. When re-shown to a
 *       viewer, there is a brief window where the client has the spawn
 *       packet but not yet the invisibility metadata, so the stand's model
 *       flashes visible for a frame. A {@code TextDisplay} has no body
 *       model at all (it only ever renders its text), so there is nothing
 *       to flash regardless of packet ordering.</li>
 *   <li><b>Passenger-mountable:</b> Display entities support the normal
 *       vehicle/passenger relationship, which is what makes tracking
 *       actually solid (see below).</li>
 * </ul>
 *
 * <h2>Why the tag never drifts, bounces, or glides</h2>
 * <p>Earlier versions of this class re-teleported the display to the
 * player's position every tick. That can never be perfectly solid: a
 * teleport is a network position sync capped at the server's 20 ticks/sec,
 * so the client is always either snapping discretely between ticks (visible
 * as bouncing on fast Y movement like jumping) or easing between two
 * tick-old positions on its own separate interpolation curve, which doesn't
 * necessarily match the curve the client is already using to ease the
 * player's own body — so the tag visibly slides relative to the head it's
 * supposed to be fixed to.
 *
 * <p>This is solved by not teleporting the tag at all. Instead, the display
 * is added as a <b>passenger</b> of the owning player via
 * {@link Entity#addPassenger(Entity)}. Passenger position is computed
 * entirely client-side, every render frame, directly from the vehicle's own
 * already-interpolated transform — the same mechanism vanilla uses for
 * anything visibly "attached" to a moving entity (a player riding a boat,
 * a mob riding another mob). There is no extra network round trip and
 * nothing to fall out of sync: whatever curve the client is already
 * rendering the player's body on, the tag rides along on that exact curve.
 * This works identically for Java and Bedrock/Geyser clients, since it's a
 * fundamental entity relationship, not a Java-specific rendering detail.
 *
 * <p>The vertical offset above the player is applied as a
 * {@link Transformation} translation on the display (not a location
 * change), which only needs to be touched when the player's crouch state
 * actually changes — not every tick — since the horizontal/vertical
 * following itself is now free.
 *
 * <h2>Two displays per player, not one</h2>
 * <p>Every owner gets <b>two</b> passenger {@code TextDisplay} entities —
 * {@code javaDisplay} and {@code bedrockDisplay} — carrying identical text
 * but two independently-tuned {@link Transformation} translations. Each
 * viewer is only ever shown the one entity matching their own client
 * ({@link BedrockDetector#isBedrockPlayer}); the other is always hidden to
 * them via {@code hideEntity}.
 *
 * <p>This exists because the Java/Bedrock rendering gap that
 * {@link ConfigManager#getBedrockHeightAdjust()} corrects for is a property
 * of <b>how the viewer's client</b> (vanilla Java vs. Geyser) renders a
 * passenger-mounted display — it is not a property of which platform the
 * <em>ridden player</em> happens to be on. A single {@code Transformation}
 * is entity metadata broadcast identically to every viewer, so there is no
 * way to key that correction off the viewer with only one entity: an
 * earlier version of this code keyed it off the owner's platform instead
 * (as a stand-in), which get all three cross-platform cases wrong at once —
 * a Bedrock viewer watching a Java owner crouch got no correction at all
 * (too low), while a Java viewer watching a Bedrock owner got a correction
 * that was never meant for them (too low standing, too high crouching).
 * Maintaining one real entity per viewer platform lets each viewer's
 * {@link Transformation} be tuned purely for their own client, regardless
 * of who they're looking at.
 *
 * Visibility (Paper API):
 * <ul>
 *   <li>{@code setVisibleByDefault(false)}</li>
 *   <li>{@code showEntity} for the one display matching each online
 *       viewer's platform, {@code hideEntity} for the other, for every
 *       viewer except the owner (sneaking dims the tag rather than hiding
 *       it — see below)</li>
 *   <li>Owner never sees either of their own tags (cannot see own nametag
 *       when looking up)</li>
 * </ul>
 *
 * Standing vs. sneaking look:
 * <ul>
 *   <li><b>Standing:</b> full-brightness, fully-opaque text. For Java
 *       viewers, switches into "see-through" render mode only while at
 *       least one Java viewer within render distance is actually being
 *       blocked from seeing the tag by a wall — see
 *       {@link #anyJavaViewerOccluded} for why this is a same-for-everyone
 *       approximation rather than truly per-viewer. Bedrock/Geyser viewers
 *       always see the tag occluded by blocks like vanilla.</li>
 *   <li><b>Sneaking:</b> text colors darkened (component rewrite), reduced
 *       opacity, and per-viewer line-of-sight checks so the tag stays visible
 *       in the open but is hidden when a wall blocks the view. Works for both
 *       Java and Bedrock viewers without respawning the entity.</li>
 * </ul>
 * <p>Nametag height tracks eye height so the gap above the head stays
 * constant when crouching. Because this is now a {@link Transformation}
 * change on an already-attached passenger (not a position teleport), the
 * crouch/uncrouch height change can never produce the "spawns high, glides
 * down" glitch older teleport-based approaches had to work around — the
 * passenger relationship itself is untouched, only the local offset eases.
 */
public final class NametagDisplayManager {

    /**
     * Reference value used to cancel out the vehicle's default passenger
     * attachment offset (see {@link #buildTransformation}). Deliberately
     * <b>not</b> recomputed per-pose from a live bounding box: testing
     * showed the client's actual default attach point does not shrink when
     * the player crouches, so an offset that dynamically shrunk with the
     * live bounding box (as an earlier version of this code did) double
     * counted the crouch height change — the tag ended up too low while
     * crouched and then overshot too high on uncrouch, since the
     * correction and the real (unmoving) attach point were fighting each
     * other. Using one fixed value for every pose and letting the
     * translation below carry 100% of the pose-dependent height change
     * fixes both directions at once.
     *
     * <p>This value only ever needs to be "in the right ballpark" — it's
     * subtracted in {@link #buildTransformation} and then (approximately)
     * re-added by the client's own real attach point, so any small error in
     * this constant mostly cancels out against that real (but API-invisible)
     * attach point and was never noticeable while continuously mounted.
     * This constant is only ever used while actually mounted; a dismounted
     * display is positioned via {@link #dismountedRenderLocation} instead,
     * which deliberately reuses the exact same {@link #heightAboveFeet}
     * formula as {@link #buildTransformation} (just measured from the
     * player's feet instead of folded into a passenger-local offset) so the
     * two rendering paths can never disagree with each other at the instant
     * of a dismount/remount, regardless of how accurate this constant is.
     */
    private static final double ASSUMED_MOUNT_OFFSET = 1.8;

    /**
     * Vanilla standing / sneaking eye heights. Fixed values (not live
     * {@code getEyeHeight()}) so stand↔crouch snaps to the final pose in one
     * transformation change instead of tracking intermediate eye heights.
     */
    private static final double STANDING_EYE_HEIGHT = 1.62;
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    /**
     * How many ticks the crouch↔stand height change eases over for
     * {@code javaDisplay}, via a native {@link
     * Display#setInterpolationDuration(int)} transformation ease. Unrelated
     * to position tracking (which the passenger relationship handles for
     * free), so it only plays when the owner's sneak state actually flips,
     * not on every movement tick.
     *
     * <p>{@code bedrockDisplay} does not use this at all — see
     * {@link #snapBedrockHeight} for why Bedrock viewers get an instant
     * height change instead of an eased one.
     */
    private static final int HEIGHT_TRANSITION_TICKS = 4;

    /**
     * How often (in ticks) {@link #tickMaintain} re-checks render-distance
     * visibility and wall-occlusion see-through state for a standing
     * player. Unlike sneak-state changes (which must react on the very tick
     * they happen), a viewer walking into/out of render range or stepping
     * behind a wall doesn't need per-tick precision — a few ticks of lag is
     * imperceptible, and checking every online viewer against every owner
     * this often instead of every tick meaningfully cuts the cost on
     * populated servers.
     */
    private static final long SEE_THROUGH_REFRESH_INTERVAL_TICKS = 4;

    /** Identity rotation, reused for every {@link Transformation} we build. */
    private static final Quaternionf IDENTITY_ROTATION = new Quaternionf();

    /** Uniform 1:1 scale, reused for every {@link Transformation} we build. */
    private static final Vector3f UNIT_SCALE = new Vector3f(1f, 1f, 1f);

    /**
     * Deliberately <b>not</b> touching the display's {@link Transformation}
     * on dismount/remount was a late fix — see {@link #dismountOne} and
     * {@link #dismountedRenderLocation} for why. An earlier version zeroed
     * the Transformation while dismounted (reasoning: the absolute teleport
     * target already bakes in the full height, so the per-pose translation
     * would double-count if left in place) and restored the real one on
     * remount. That was correct in principle but wrong in practice: the
     * Transformation is entity metadata, sent to the client as a separate
     * packet from the Set Passengers (mount/unmount) packet, and Minecraft
     * gives no guarantee the two land in the same client render frame. That
     * left a real window — sometimes a frame, sometimes longer under load —
     * where the client had received one change but not the other: e.g.
     * "already re-mounted" + "still zeroed Transformation" (tag snaps to
     * the vehicle's bare attach point, too low — the Bedrock "pops down")
     * or "already restored Transformation" + "not yet re-mounted"
     * (translation applied on top of the already-full absolute height, too
     * high, then corrected the instant the mount packet lands — the Java
     * "pops up and back down"). Every command a player ran flipped both of
     * these at least once, which is exactly this class's old popping.
     *
     * <p>The fix is to never change the Transformation for a dismount at
     * all — {@link #dismountedRenderLocation} is calibrated (via
     * {@link #ASSUMED_MOUNT_OFFSET}) so that "absolute position + whatever
     * Transformation is already on the entity" comes out to the exact same
     * on-screen height whether or not the entity happens to be mounted at
     * that instant. With nothing left to race, there's no packet-ordering
     * window left for a pop to hide in.
     */

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

    /**
     * Fixed light-level override applied to every nametag TextDisplay.
     *
     * <p>Without this, the client computes the entity's render brightness
     * from the actual block/sky light at its position — <em>except</em>
     * when {@code see_through} is active, in which case vanilla rendering
     * ignores that computed light level and draws the text at full
     * brightness instead so it reads clearly through blocks. That makes a
     * tag flip to noticeably brighter the instant a viewer loses direct
     * line of sight (e.g. stepping behind a wall), then flip back once
     * they regain it.
     *
     * <p>Setting an explicit {@link Display.Brightness} pins the light
     * level used for rendering regardless of {@code see_through} or actual
     * world lighting, so the tag looks the same whether or not it's being
     * drawn through a wall.
     */
    private static final Display.Brightness NAMETAG_BRIGHTNESS = new Display.Brightness(15, 15);

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;

    private NametagManager nametagManager;

    /** Per-owner pair of passenger displays: one tuned for Java viewers, one for Bedrock viewers. */
    private final Map<UUID, DisplayPair> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastSneaking = new ConcurrentHashMap<>();
    /**
     * Set by an explicit {@link #dismount(UUID, long)} call to the server tick
     * at which {@link #tickMaintain()} should re-attach the passenger. Without this,
     * {@code tickMaintain} re-mounts on the very next tick regardless of why
     * the tag was dismounted. The dismount duration determines how many ticks
     * to keep the player's nametag off. This is used for any command execution
     * to ensure teleport commands or other operations complete without the
     * passenger blocking them.
     */
    private final Map<UUID, Long> dismountUntilTick = new ConcurrentHashMap<>();
    /**
     * Current server tick, incremented every time {@link #tickMaintain()} runs.
     * Used to determine when a dismount window expires.
     */
    private long currentTick = 0;
    /** Last full-brightness nametag text per player (used to rebuild a dimmed copy while sneaking). */
    private final Map<UUID, Component> brightTexts = new ConcurrentHashMap<>();
    private BukkitTask followTask;

    /** The two viewer-platform-specific passenger displays owned by one player. */
    private static final class DisplayPair {
        final TextDisplay javaDisplay;
        final TextDisplay bedrockDisplay;

        /**
         * Bedrock viewer UUIDs that currently have {@code bedrockDisplay}
         * hidden from them via {@code hideEntity} (LOS-blocked while the
         * owner sneaks). Tracked per-viewer — not just per-owner — because
         * whether any one viewer can currently see the tag depends on that
         * viewer's own line of sight, so different Bedrock viewers of the
         * same owner can be hidden/shown independently at the same instant.
         * Used solely to detect the hidden→shown transition (see
         * {@link #showOneToViewer}).
         */
        final Set<UUID> bedrockHiddenFrom = ConcurrentHashMap.newKeySet();

        DisplayPair(TextDisplay javaDisplay, TextDisplay bedrockDisplay) {
            this.javaDisplay = javaDisplay;
            this.bedrockDisplay = bedrockDisplay;
        }

        boolean isValid() {
            return javaDisplay.isValid() && bedrockDisplay.isValid();
        }

        boolean isInWorld(World world) {
            return javaDisplay.getWorld() == world && bedrockDisplay.getWorld() == world;
        }
    }

    public NametagDisplayManager(CustomPlayerNametags plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setNametagManager(NametagManager nametagManager) {
        this.nametagManager = nametagManager;
    }

    public void start() {
        stopFollowTask();
        // No longer a position-tracking loop (the passenger relationship
        // handles that for free) — this now only watches for the mount
        // relationship being broken (world changes, other plugins/vehicles
        // dismounting the tag) and keeps sneak-triggered visibility fresh.
        this.followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMaintain, 1L, 1L);
    }

    public void shutdown() {
        stopFollowTask();
        for (UUID uuid : displays.keySet().toArray(new UUID[0])) {
            remove(uuid);
        }
        displays.clear();
        lastSneaking.clear();
        brightTexts.clear();
    }

    private void stopFollowTask() {
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
    }

    /**
     * @param forceAppearance when true, always runs the full
     *                        {@link #applySneakState} pass (height,
     *                        opacity, transformation, viewer visibility)
     *                        even if the sneak state hasn't changed since
     *                        the last call. Used by {@code /nametags
     *                        reload} so height/appearance settings that
     *                        just changed in config.yml take effect
     *                        immediately, instead of waiting for the
     *                        player's next crouch/uncrouch — the periodic
     *                        per-second refresh passes {@code false} here
     *                        since it only needs the cheap text-only path.
     */
    public void update(Player target, String fullLegacyText, boolean forceAppearance) {
        if (!target.isOnline()) {
            remove(target.getUniqueId());
            return;
        }

        Component nameComponent = LegacyComponentSerializer.legacySection().deserialize(
                fullLegacyText == null ? "" : fullLegacyText);
        brightTexts.put(target.getUniqueId(), nameComponent);

        DisplayPair pair = displays.get(target.getUniqueId());
        if (pair == null || !pair.isValid() || !pair.isInWorld(target.getWorld())) {
            if (pair != null) {
                remove(target.getUniqueId());
            }
            pair = spawn(target, nameComponent);
            if (pair == null) {
                return;
            }
            displays.put(target.getUniqueId(), pair);
        } else {
            applyAppearance(pair);
            // The periodic per-second refresh (NametagManager's refresh
            // task) calls update() independently of tickMaintain()'s own
            // 1-tick loop — it is NOT gated on the dismount window at all.
            // Unconditionally calling ensureMounted() here re-attaches the
            // passenger mid-dismount whenever this refresh happens to land
            // inside the window opened by dismount(), silently undoing it
            // and leaving the teleport blocked again a moment later. This
            // is why the failure was intermittent (~1 in REFRESH_INTERVAL_TICKS
            // chance of the refresh landing inside the dismount window)
            // rather than constant. Skipping the remount here while a
            // dismount is active — mirroring the same check tickMaintain()
            // already does — closes that race.
            Long dismountUntil = dismountUntilTick.get(target.getUniqueId());
            boolean dismountActive = dismountUntil != null && currentTick < dismountUntil;
            if (!dismountActive && !ensureMounted(target, pair)) {
                // addPassenger() declined the mount (most often the
                // player's own entity state hadn't fully settled yet in
                // the tick right after a teleport). Retrying the exact
                // same addPassenger() call again next time is what used to
                // leave the tag permanently detached whenever the
                // underlying condition didn't clear on its own — there was
                // nothing to notice the failure and try something else.
                // Falling back to a full respawn here guarantees recovery:
                // a freshly spawned pair mounts from scratch rather than
                // depending on the old entities ever becoming mountable.
                remove(target.getUniqueId());
                pair = spawn(target, nameComponent);
                if (pair == null) {
                    return;
                }
                displays.put(target.getUniqueId(), pair);
            }
            boolean sneaking = target.isSneaking();
            Boolean previous = lastSneaking.put(target.getUniqueId(), sneaking);
            if (dismountActive) {
                // Tag is mid command-dismount: text/opacity can still
                // usefully refresh, but never touch the Transformation
                // here. Height while dismounted comes entirely from
                // tickMaintain()'s per-tick teleport to the player's live
                // eye location (see updateDismountedPosition) — setting a
                // "mounted" Transformation on top of that here would
                // double-count the height and produce a visible jump
                // before the dismount window has even ended.
                refreshTextOnly(target, pair, sneaking);
            } else if (forceAppearance || previous == null || previous != sneaking) {
                // Sneak flipped (or a forced refresh was requested) — must
                // go through the full transition (text dim, opacity, height
                // ease, LOS-aware Bedrock reveal, etc).
                applySneakState(target, pair, sneaking);
            } else {
                // Sneak state unchanged: only refresh the text/opacity. Do NOT
                // call applySneakState() here — it re-runs the full
                // visibility dance every time, which this method is invoked
                // from periodically (once a second, regardless of whether
                // anything actually changed). Calling it unconditionally
                // caused the tag to visibly hide and re-show every second for
                // Bedrock viewers watching through a wall, even though
                // nothing about the pose or visibility needed to change.
                refreshTextOnly(target, pair, sneaking);
            }
        }
    }

    /**
     * Dismount a player's nametag for the specified number of ticks. The tag
     * will remain dismounted (passenger link removed) until the specified tick
     * duration has elapsed. This is used when a player runs a command to prevent
     * the nametag passenger from blocking teleportation or other command execution.
     *
     * <p>The nametag will automatically remount after the duration expires.
     *
     * @param uuid the player's UUID
     * @param durationTicks how many ticks to keep the nametag dismounted
     */
    public void dismount(UUID uuid, long durationTicks) {
        dismountUntilTick.put(uuid, currentTick + durationTicks);
        DisplayPair pair = displays.get(uuid);
        if (pair == null) {
            return;
        }
        // Resolve the owner and remove from the OWNER's passenger list
        // directly, rather than relying solely on display.getVehicle().
        // getVehicle() and the owner's own passenger list can briefly
        // desync (see the identical note in removeOne() below); trusting
        // only the display-side pointer meant that whenever it was null or
        // stale at the exact instant this ran, removePassenger() never got
        // called and the owner was left still carrying the passenger —
        // which is exactly what silently blocks a same-tick cross-world
        // teleport immediately afterward. Clearing both sides closes that
        // race so the dismount is guaranteed to have actually taken effect
        // by the time this method returns.
        Player owner = Bukkit.getPlayer(uuid);
        dismountOne(owner, pair.javaDisplay, false);
        dismountOne(owner, pair.bedrockDisplay, true);
    }

    private void dismountOne(Player owner, TextDisplay display, boolean forBedrockViewer) {
        if (display == null || !display.isValid()) {
            return;
        }
        if (owner != null) {
            owner.removePassenger(display);
        }
        Entity vehicle = display.getVehicle();
        if (vehicle != null && vehicle != owner) {
            vehicle.removePassenger(display);
        }
        // While mounted, the display's own server-side location is never
        // touched — the class javadoc explains why (position is derived
        // entirely client-side from the vehicle's transform). That means
        // the entity's actual coordinates are still whatever they were set
        // to at spawn() (or the last world-change respawn), which by now
        // can be nowhere near the player. The instant it's detached above,
        // the client stops deriving its position from the owner and falls
        // back to that stale, real location — so without this, the tag
        // doesn't just hold still at the player's current spot, it snaps
        // to wherever it was left behind.
        //
        // Deliberately does NOT touch the display's Transformation here —
        // see the field javadoc above (formerly FLAT_TRANSFORMATION) for
        // why that used to cause the up/down popping on every command.
        // dismountedRenderLocation() is calibrated so that teleporting here
        // reproduces the exact same on-screen spot the tag was already
        // sitting at the instant before, using whatever Transformation
        // happens to already be on the entity — mounted or not.
        if (owner != null) {
            display.teleport(dismountedRenderLocation(owner, forBedrockViewer));
        }
    }

    /**
     * Where a dismounted (temporarily un-passengered) display should render,
     * expressed in absolute world coordinates.
     *
     * <p>For the Java display, this is just {@link #ASSUMED_MOUNT_OFFSET}
     * above the owner's feet — see the field javadoc above for why that
     * cancels out against whatever {@link Transformation} is already on the
     * entity, mounted or not, regardless of sneak state.
     *
     * <p>The Bedrock display adds {@link ConfigManager#getBedrockDismountHeightAdjust()}
     * on top of that same reference point. Java's passenger-mount math is
     * public and predictable, which is why one shared constant works for
     * it. Geyser has to reimplement passenger mounting for Bedrock from
     * scratch (display entities don't exist natively there), and there's no
     * public spec for exactly what height its translation lands on — so
     * unlike the Java side, this can't be derived analytically. That's what
     * the config option is for: an admin-tunable correction found by
     * testing on a real Bedrock client, the same way {@link
     * ConfigManager#getBedrockHeightAdjust()} already is for continuous
     * mounted rendering. Defaults to {@code 0.0} (behaves identically to
     * Java) until tuned.
     */
    private Location dismountedRenderLocation(Player owner, boolean forBedrockViewer) {
        double offset = ASSUMED_MOUNT_OFFSET
                + (forBedrockViewer ? config.getBedrockDismountHeightAdjust() : 0.0);
        return owner.getLocation().add(0.0, offset, 0.0);
    }

    /**
     * Re-teleports both of a dismounted owner's displays to {@link
     * #dismountedRenderLocation} every tick the dismount window is still
     * active (called from {@link #tickMaintain}). Without this, a command
     * dismount placed the tag once and then left it completely frozen in
     * place for the rest of dismount-duration-ticks — so
     * a player who moved at all while a command was
     * processing saw their own tag get left behind in midair, then visibly
     * snap back into place the instant it remounted. Continuously tracking
     * the player's live position here keeps the tag glued to them the
     * entire time instead, exactly as if it were still mounted.
     */
    private void updateDismountedPosition(Player owner, DisplayPair pair) {
        if (pair.javaDisplay.isValid()) {
            pair.javaDisplay.teleport(dismountedRenderLocation(owner, false));
        }
        if (pair.bedrockDisplay.isValid()) {
            pair.bedrockDisplay.teleport(dismountedRenderLocation(owner, true));
        }
    }

    public void remove(UUID uuid) {
        DisplayPair pair = displays.remove(uuid);
        lastSneaking.remove(uuid);
        brightTexts.remove(uuid);
        dismountUntilTick.remove(uuid);
        if (pair != null) {
            Player owner = Bukkit.getPlayer(uuid);
            removeOne(owner, pair.javaDisplay);
            removeOne(owner, pair.bedrockDisplay);
        }
    }

    private void removeOne(Player owner, TextDisplay display) {
        if (display != null && display.isValid()) {
            // Explicitly break the passenger link first, from BOTH sides.
            // Entity#remove() does not guarantee the vehicle's (the owning
            // player's) passenger list is cleared in the same synchronous
            // call — that stale reference is long enough for Bukkit's
            // cross-world teleport handling to still see the player as
            // carrying a passenger and block the teleport, even when this
            // remove() is called from PlayerConnectionListener#onTeleport
            // right before the actual move. Relying only on
            // display.getVehicle() isn't enough on its own — that pointer
            // and the owner's own passenger list can themselves desync —
            // so the owner's list is cleared directly first, with
            // getVehicle() as a fallback for the (rare) case the owner
            // reference isn't available.
            if (owner != null) {
                owner.removePassenger(display);
            }
            Entity vehicle = display.getVehicle();
            if (vehicle != null && vehicle != owner) {
                vehicle.removePassenger(display);
            }
            display.remove();
        }
    }

    /** Shows every existing display to a viewer except their own (see class javadoc for sneak visibility rules). */
    public void showExistingTo(Player viewer) {
        for (Map.Entry<UUID, DisplayPair> entry : displays.entrySet()) {
            if (entry.getKey().equals(viewer.getUniqueId())) {
                continue;
            }
            DisplayPair pair = entry.getValue();
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (pair == null || !pair.isValid() || owner == null) {
                continue;
            }
            showOneToViewer(owner, pair, viewer);
        }
    }

    /**
     * Shows the single display matching {@code viewer}'s own platform and
     * hides the other, for one owner/viewer pair. Sneak-triggered
     * line-of-sight occlusion only ever applies to the Bedrock display for a
     * Bedrock viewer (see class javadoc — Java relies on {@code seeThrough}
     * instead and is never hidden here).
     */
    private void showOneToViewer(Player owner, DisplayPair pair, Player viewer) {
        if (!withinRenderDistance(owner, viewer) || !viewer.canSee(owner)) {
            // Either out of range, or the owner is invisible to this
            // specific viewer — most commonly because the owner vanished
            // (vanish plugins hide a player via Player#hidePlayer, which is
            // exactly what Player#canSee reflects). The passenger displays
            // are separate entities from the owner, so nothing else here
            // would ever notice the owner disappeared; without this check
            // the tag was left mounted and visible, floating at the spot
            // the owner vanished from, until an unrelated refresh (e.g. the
            // owner crouching) happened to touch this viewer's visibility.
            // Checked unconditionally every tick (see tickMaintain), so the
            // tag disappears immediately instead of on some delay.
            viewer.hideEntity(plugin, pair.javaDisplay);
            viewer.hideEntity(plugin, pair.bedrockDisplay);
            pair.bedrockHiddenFrom.remove(viewer.getUniqueId());
            return;
        }

        boolean viewerIsBedrock = BedrockDetector.isBedrockPlayer(viewer);
        boolean sneaking = owner.isSneaking();

        if (viewerIsBedrock) {
            UUID viewerId = viewer.getUniqueId();
            boolean shouldHide = sneaking && !hasLineOfSight(viewer, effectiveLocation(owner));
            if (shouldHide) {
                viewer.hideEntity(plugin, pair.bedrockDisplay);
                pair.bedrockHiddenFrom.add(viewerId);
            } else {
                if (pair.bedrockHiddenFrom.remove(viewerId)) {
                    // This viewer's client just had the display destroyed
                    // (re-tracking after being LOS-hidden), so make sure it
                    // reappears already at the correct height rather than
                    // whatever stale value was last set before it was hidden.
                    pair.bedrockDisplay.setInterpolationDelay(0);
                    pair.bedrockDisplay.setInterpolationDuration(0);
                    pair.bedrockDisplay.setTransformation(buildTransformation(sneaking, true));
                }
                viewer.showEntity(plugin, pair.bedrockDisplay);
            }
            viewer.hideEntity(plugin, pair.javaDisplay);
        } else {
            viewer.showEntity(plugin, pair.javaDisplay);
            viewer.hideEntity(plugin, pair.bedrockDisplay);
        }
    }

    private DisplayPair spawn(Player owner, Component name) {
        brightTexts.put(owner.getUniqueId(), name);
        boolean sneaking = owner.isSneaking();
        lastSneaking.put(owner.getUniqueId(), sneaking);

        Location loc = owner.getLocation();
        TextDisplay javaDisplay = createDisplay(owner, name, loc, sneaking, false);
        TextDisplay bedrockDisplay = createDisplay(owner, name, loc, sneaking, true);
        if (javaDisplay == null || bedrockDisplay == null) {
            if (javaDisplay != null) {
                javaDisplay.remove();
            }
            if (bedrockDisplay != null) {
                bedrockDisplay.remove();
            }
            return null;
        }

        owner.addPassenger(javaDisplay);
        owner.addPassenger(bedrockDisplay);

        DisplayPair pair = new DisplayPair(javaDisplay, bedrockDisplay);
        applyViewerVisibility(owner, pair, sneaking);
        return pair;
    }

    /**
     * Creates a TextDisplay with all nametag properties, including the
     * current sneak see-through / opacity / dimmed text and height
     * transformation, applied inside the spawn consumer so they land on the
     * initial metadata packet.
     *
     * @param forBedrockViewer whether this specific entity is the one shown
     *                          to Bedrock/Geyser viewers (as opposed to Java
     *                          viewers) — controls which height correction
     *                          applies (see {@link #buildTransformation}).
     */
    private TextDisplay createDisplay(Player owner, Component bright, Location loc, boolean sneaking,
                                      boolean forBedrockViewer) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        final Component displayText = sneaking ? dim(bright, forBedrockViewer) : bright;
        final Transformation transformation = buildTransformation(sneaking, forBedrockViewer);

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
            // Pin the render light level so toggling see_through (below)
            // never causes the text to jump to a different brightness —
            // see NAMETAG_BRIGHTNESS javadoc.
            entity.setBrightness(NAMETAG_BRIGHTNESS);
            // No easing on the very first frame — nothing to ease from yet.
            entity.setInterpolationDelay(0);
            entity.setInterpolationDuration(0);
            entity.setTransformation(transformation);
            // Must be set here (pre-track) so Java clients receive see_through
            // on the spawn metadata packet. Later in-place changes are ignored.
            entity.setSeeThrough(effectiveSeeThrough(sneaking, anyJavaViewerOccluded(owner)));
            entity.setTextOpacity(sneaking ? OPACITY_SNEAKING : OPACITY_STANDING);
        });
    }

    /**
     * Re-attaches both displays as passengers of {@code owner} if something
     * (a world change, another plugin, the owner entering/leaving a
     * vehicle, etc) broke the mount relationship. Cheap no-op in the
     * overwhelmingly common case where they're already mounted correctly.
     *
     * @return true if both displays are confirmed mounted after this call,
     *         false if {@link Entity#addPassenger} declined one or both
     *         (e.g. the owner's chunk/entity state hadn't finished settling
     *         yet right after a teleport). Callers use this to detect a
     *         mount that didn't actually take instead of assuming it did —
     *         see the callers for why that matters.
     */
    private boolean ensureMounted(Player owner, DisplayPair pair) {
        List<Entity> passengers = owner.getPassengers();
        if (!passengers.contains(pair.javaDisplay)) {
            owner.addPassenger(pair.javaDisplay);
        }
        if (!passengers.contains(pair.bedrockDisplay)) {
            owner.addPassenger(pair.bedrockDisplay);
        }
        List<Entity> after = owner.getPassengers();
        return after.contains(pair.javaDisplay) && after.contains(pair.bedrockDisplay);
    }

    /**
     * Updates only the displayed text/opacity for the current sneak state,
     * without touching height or per-viewer visibility. Used for periodic
     * refreshes (e.g. placeholder text changes) where the sneak state itself
     * hasn't changed, so none of the visibility machinery in
     * {@link #applySneakState} needs to run again.
     */
    private void refreshTextOnly(Player owner, DisplayPair pair, boolean sneaking) {
        Component bright = brightTexts.get(owner.getUniqueId());
        if (bright == null) {
            bright = pair.javaDisplay.text();
            brightTexts.put(owner.getUniqueId(), bright);
        }
        Component javaText = sneaking ? dim(bright, false) : bright;
        Component bedrockText = sneaking ? dim(bright, true) : bright;
        byte opacity = sneaking ? OPACITY_SNEAKING : OPACITY_STANDING;
        pair.javaDisplay.text(javaText);
        pair.javaDisplay.setTextOpacity(opacity);
        pair.bedrockDisplay.text(bedrockText);
        pair.bedrockDisplay.setTextOpacity(opacity);
    }

    /**
     * Re-checks {@link #anyJavaViewerOccluded} for a standing owner and
     * applies the result to {@code javaDisplay}'s {@code see_through} flag.
     * Called periodically from {@link #tickMaintain} (not on sneak-state
     * change, which already goes through {@link #applySneakState} instead)
     * so the wall-occlusion effect turns on/off as viewers move behind
     * walls or in/out of render range, without waiting for the owner to
     * crouch. Never touches height, opacity, or text.
     */
    private void refreshSeeThroughState(Player owner, DisplayPair pair) {
        boolean seeThrough = effectiveSeeThrough(false, anyJavaViewerOccluded(owner));
        pair.javaDisplay.setSeeThrough(seeThrough);
    }

    /**
     * Applies standing-vs-sneaking appearance on both custom TextDisplays.
     * Always keeps the configured format (never falls back to the raw username).
     *
     * <p>Crouch: grey text + reduced opacity + LOS occlusion for Bedrock viewers.
     * Standing: full-colour text + full opacity + wall-occlusion-gated see-through.
     *
     * <p>The height change itself is a {@link Transformation} ease on the
     * already-mounted passengers — never a position teleport — so there's
     * nothing for it to glitch against.
     */
    private void applySneakState(Player owner, DisplayPair pair, boolean sneaking) {
        Component bright = brightTexts.get(owner.getUniqueId());
        if (bright == null) {
            bright = pair.javaDisplay.text();
            brightTexts.put(owner.getUniqueId(), bright);
        }

        // Ensure vanilla nametag stays hidden (custom TextDisplay is the only tag).
        if (nametagManager != null) {
            nametagManager.setVanillaNametagHidden(owner, true);
        }

        Component javaText = sneaking ? dim(bright, false) : bright;
        Component bedrockText = sneaking ? dim(bright, true) : bright;
        byte opacity = sneaking ? OPACITY_SNEAKING : OPACITY_STANDING;
        boolean occluded = anyJavaViewerOccluded(owner);

        applySneakStateToOne(pair.javaDisplay, javaText, opacity, sneaking, false, occluded);
        applySneakStateToOne(pair.bedrockDisplay, bedrockText, opacity, sneaking, true, occluded);

        applyViewerVisibility(owner, pair, sneaking);
    }

    private void applySneakStateToOne(TextDisplay display, Component text, byte opacity,
                                      boolean sneaking, boolean forBedrockViewer, boolean occluded) {
        display.setSeeThrough(effectiveSeeThrough(sneaking, occluded));

        if (forBedrockViewer) {
            display.text(text);
            display.setTextOpacity(opacity);
            snapBedrockHeight(display, buildTransformation(sneaking, true));
            return;
        }

        if (sneaking) {
            // Going into a crouch: text/opacity dim together with the
            // height ease, as before — only the uncrouch case below needed
            // to change.
            display.text(text);
            display.setTextOpacity(opacity);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(HEIGHT_TRANSITION_TICKS);
            display.setTransformation(buildTransformation(true, false));
            return;
        }

        // Uncrouching: Display entities generically interpolate whichever
        // fields actually changed in a metadata update once
        // interpolation_duration is >0 for that update — not just the
        // transformation. Text/opacity were being bundled into the very
        // same update as the height-ease transformation below, so a Java
        // viewer saw the color lerp back to full brightness over
        // HEIGHT_TRANSITION_TICKS right along with the height, instead of
        // snapping back immediately.
        //
        // Fix: send the text/opacity change on its own first, with
        // interpolation_duration forced to 0 so the client applies it
        // instantly, then apply the height ease as a *separate* metadata
        // update one tick later. By the time that second update goes out,
        // text/opacity are already unchanged (nothing left to interpolate),
        // so only the height actually eases.
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.text(text);
        display.setTextOpacity(opacity);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (display.isValid()) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(HEIGHT_TRANSITION_TICKS);
                display.setTransformation(buildTransformation(false, false));
            }
        });
    }

    /**
     * Java viewers only (Geyser ignores {@code see_through} entirely — see
     * {@link #hasLineOfSight}). While the owner is standing, the tag only
     * switches into Minecraft's "see through" render mode when at least one
     * Java viewer within {@link ConfigManager#getNametagRenderDistance()}
     * currently has their line of sight to the tag blocked by a wall — see
     * {@link #anyJavaViewerOccluded}. Sneaking always forces it off, going
     * back to normal depth-tested rendering (occluded by walls like any
     * other entity, matching vanilla).
     */
    private boolean effectiveSeeThrough(boolean sneaking, boolean occludedForSomeViewer) {
        return !sneaking && occludedForSomeViewer;
    }

    /**
     * {@code see_through} is entity-wide metadata — Paper has no way to send
     * a different value of it to different viewers of the same entity — so
     * this can only be an aggregate approximation of "being looked at
     * through a wall": if <em>any</em> online Java viewer within render
     * distance currently has {@code owner}'s tag blocked from their line of
     * sight, see-through switches on for every viewer who can see the tag,
     * not just the one who's actually behind a wall. That's the closest
     * this can get without spawning a separate entity per viewer.
     */
    private boolean anyJavaViewerOccluded(Player owner) {
        Location tagLocation = effectiveLocation(owner);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            if (BedrockDetector.isBedrockPlayer(viewer)) {
                continue;
            }
            if (!withinRenderDistance(owner, viewer)) {
                continue;
            }
            if (!hasLineOfSight(viewer, tagLocation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code viewer} is close enough to {@code owner} to have their
     * nametag shown at all, matching vanilla's own fixed nametag render
     * cutoff rather than whatever the server's entity-tracking-range happens
     * to be set to (see {@link ConfigManager#getNametagRenderDistance()}).
     */
    private boolean withinRenderDistance(Player owner, Player viewer) {
        if (owner.getWorld() != viewer.getWorld()) {
            return false;
        }
        double max = config.getNametagRenderDistance();
        return owner.getLocation().distanceSquared(viewer.getLocation()) <= max * max;
    }

    /**
     * Sets {@code display} (the Bedrock-viewer entity) straight to
     * {@code target}'s height with no animation at all, unlike
     * {@code javaDisplay} which eases via native {@code interpolation_duration}
     * metadata in {@link #applySneakStateToOne}.
     *
     * <p>An earlier version of this tried to fake that same ease for Bedrock
     * by manually stepping the translation once per tick over several ticks,
     * to work around Geyser not reliably carrying native Display
     * interpolation through intact. That made things worse, not better: a
     * Bedrock client doesn't tween between those manual per-tick updates the
     * way it would a single interpolated change, so each step rendered as
     * its own separate, independent pop — four visible jumps in quick
     * succession instead of one. A single flat update is the only version of
     * this that actually looks clean on Bedrock: one instant, correct snap,
     * with nothing in between to stutter on.
     */
    private void snapBedrockHeight(TextDisplay display, Transformation target) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTransformation(target);
    }

    /**
     * Per-viewer visibility for a nametag's pair of displays.
     *
     * <ul>
     *   <li>Owner never sees either of their own tags.</li>
     *   <li>Java viewers: always shown the {@code javaDisplay}, never the
     *       {@code bedrockDisplay}. Occlusion while sneaking is handled
     *       purely by {@code seeThrough=false} on the TextDisplay — do
     *       <em>not</em> hideEntity, or the client re-spawns the entity on
     *       uncrouch and the tag jumps (especially when viewed through a
     *       wall).</li>
     *   <li>Bedrock viewers: always shown the {@code bedrockDisplay}, never
     *       the {@code javaDisplay}. Geyser ignores {@code see_through}, so
     *       while the owner is sneaking we LOS-hide the Bedrock display when
     *       blocked by blocks.</li>
     * </ul>
     */
    private void applyViewerVisibility(Player owner, DisplayPair pair, boolean sneaking) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                viewer.hideEntity(plugin, pair.javaDisplay);
                viewer.hideEntity(plugin, pair.bedrockDisplay);
                continue;
            }
            showOneToViewer(owner, pair, viewer);
        }
    }

    /**
     * Approximate world-space location of the tag, for line-of-sight checks
     * only. Based purely on the owner's own server-side position/pose, which
     * is accurate enough for a blocks-only raytrace — the client-side
     * rendering precision the passenger relationship buys us doesn't matter
     * for this server-side check.
     */
    private Location effectiveLocation(Player owner) {
        double eye = SNEAK_EYE_HEIGHT + (owner.isSneaking() ? 0.0 : (STANDING_EYE_HEIGHT - SNEAK_EYE_HEIGHT));
        return owner.getLocation().add(0.0, eye, 0.0);
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
    /**
     * Solid, near-white grey used for the crouch nametag on Java viewers.
     * Kept light rather than a dark/mid grey so it stays readable — a
     * darker grey was hard to make out, especially for Bedrock/Geyser
     * viewers.
     */
    private static final TextColor SNEAK_GREY = TextColor.color(225, 225, 225);

    /**
     * Slightly darker, more grey crouch color used only for the
     * Bedrock-viewed display. Same idea as {@link #SNEAK_GREY} but a touch
     * more muted for Bedrock/Geyser viewers specifically.
     */
    private static final TextColor SNEAK_GREY_BEDROCK = TextColor.color(190, 190, 190);

    /**
     * Forces the whole component tree to a flat crouch grey (rank/name
     * colours are dropped while sneaking, matching vanilla's greyed
     * nametag) — {@link #SNEAK_GREY} for Java viewers, or the slightly
     * darker {@link #SNEAK_GREY_BEDROCK} when {@code forBedrockViewer} is
     * true.
     */
    private static Component dim(Component in, boolean forBedrockViewer) {
        TextColor color = forBedrockViewer ? SNEAK_GREY_BEDROCK : SNEAK_GREY;
        Style style = in.style();
        Component out = in.style(style.toBuilder().color(color).build());
        List<Component> children = in.children();
        if (!children.isEmpty()) {
            List<Component> dimmed = new ArrayList<>(children.size());
            for (Component child : children) {
                dimmed.add(dim(child, forBedrockViewer));
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
    private void applyAppearance(DisplayPair pair) {
        pair.javaDisplay.setShadowed(false);
        pair.javaDisplay.setDefaultBackground(true);
        pair.bedrockDisplay.setShadowed(false);
        pair.bedrockDisplay.setDefaultBackground(true);
    }

    /**
     * Watches the mount relationship and Bedrock LOS visibility. No longer a
     * position-tracking loop — the passenger relationship handles that for
     * free — so this is much cheaper than the old per-tick teleport loop.
     */
    private void tickMaintain() {
        currentTick++;
        for (Map.Entry<UUID, DisplayPair> entry : displays.entrySet()) {
            UUID uuid = entry.getKey();
            DisplayPair pair = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                remove(uuid);
                continue;
            }
            if (pair == null || !pair.isValid()) {
                displays.remove(uuid);
                continue;
            }
            if (!pair.isInWorld(player.getWorld())) {
                // Passengers are dropped on a world change; let the next
                // periodic update() respawn (and re-mount) the tag in the
                // new world rather than trying to move it across worlds.
                remove(uuid);
                continue;
            }

            Long dismountUntil = dismountUntilTick.get(uuid);
            if (dismountUntil != null) {
                if (currentTick < dismountUntil) {
                    // Dismount window still active — leave the passenger
                    // detached (so commands/teleports aren't blocked), but
                    // keep tracking the player's live position every tick
                    // so the tag holds its spot above their head instead of
                    // freezing wherever they happened to be when the
                    // command was typed.
                    updateDismountedPosition(player, pair);
                    continue;
                }
                dismountUntilTick.remove(uuid);
                // The window just closed. Nothing to restore here anymore —
                // dismountOne() never touched this pair's Transformation in
                // the first place (see its javadoc), so ensureMounted() below
                // can re-attach the passenger directly with no separate
                // "restore" packet that would need to land in sync with it.
            }

            if (!ensureMounted(player, pair)) {
                // addPassenger() didn't take (most often the player's own
                // entity state hadn't finished settling in the tick right
                // after a teleport/command). Nothing here was detecting
                // that failure before, so tickMaintain kept calling the
                // same failing ensureMounted() every tick forever if the
                // underlying condition never happened to clear on its own
                // — from the player's side that looked exactly like the
                // tag freezing in place and staying detached for good.
                // Forcing a full respawn through NametagManager guarantees
                // recovery instead of an indefinite retry: a freshly
                // spawned pair mounts from scratch at the player's current
                // location rather than depending on the old, stuck
                // entities ever becoming mountable again.
                remove(uuid);
                if (nametagManager != null) {
                    nametagManager.refresh(player, true);
                }
                continue;
            }

            boolean sneaking = player.isSneaking();
            Boolean previous = lastSneaking.put(uuid, sneaking);
            if (previous == null || previous != sneaking) {
                applySneakState(player, pair, sneaking);
            } else {
                // Refresh render-distance visibility, vanish (canSee), and
                // Bedrock LOS every tick regardless of sneak state — not
                // just while sneaking — so a viewer losing sight of the
                // owner (e.g. the owner vanishing) hides the tag right
                // away instead of waiting for a sneak toggle or the
                // throttled see-through pass below.
                applyViewerVisibility(player, pair, sneaking);
                if (!sneaking && currentTick % SEE_THROUGH_REFRESH_INTERVAL_TICKS == 0) {
                    // Standing: periodically refresh wall-occlusion
                    // see-through state (throttled — raytrace-heavy, see
                    // SEE_THROUGH_REFRESH_INTERVAL_TICKS).
                    refreshSeeThroughState(player, pair);
                }
            }
        }
    }

    /**
     * The intended height above the player's feet for the tag, for one
     * viewer platform and pose — every adjustment that ever affects the
     * tag's on-screen height lives here, and both {@link #buildTransformation}
     * (mounted rendering) and {@link #dismountedRenderLocation} (dismounted
     * rendering) are built directly on top of it. That shared origin is
     * what guarantees the two rendering paths can never drift apart: there
     * is only one formula for "how high should this tag be", not two
     * similar-but-not-quite-identical ones.
     */
    private double heightAboveFeet(boolean sneaking, boolean forBedrockViewer) {
        double aboveEyes = config.getNametagHeightOffset() - STANDING_EYE_HEIGHT;
        double eye = sneaking ? SNEAK_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        double desiredFromFeet = eye + aboveEyes;
        double height = desiredFromFeet;
        if (sneaking) {
            height += config.getSneakHeightAdjust();
        }
        if (forBedrockViewer) {
            height += config.getBedrockHeightAdjust();
            if (sneaking) {
                height += config.getBedrockSneakHeightAdjust();
            }
        }
        return height;
    }

    /**
     * Builds the local offset that, once the display is riding the player
     * as a passenger, puts the tag at the configured height above the
     * player's head for their current pose, from the perspective of one
     * specific viewer platform.
     *
     * <p>{@code desiredFromFeet} is the only pose-dependent term here — it
     * correctly shrinks for the sneaking pose so the gap above the eyes
     * stays constant. {@link #ASSUMED_MOUNT_OFFSET} is deliberately a fixed
     * constant regardless of pose (see its own doc) since the real vehicle
     * attach point doesn't move with crouch; letting both terms vary with
     * pose was what caused the crouch/uncrouch height to overshoot.
     *
     * <p>{@link ConfigManager#getSneakHeightAdjust()} corrects for the real
     * attach point dropping slightly while sneaking (see
     * {@link #ASSUMED_MOUNT_OFFSET}'s doc) — raise it in config.yml if the
     * tag still rests too low while crouching, lower it if it overshoots
     * high on uncrouch, and this applies identically for every viewer since
     * it's a property of the passenger attachment itself, not of any one
     * client's renderer.
     *
     * <p>{@code forBedrockViewer} instead corrects for how a
     * <b>Bedrock/Geyser client</b> renders this passenger-mounted display
     * compared to vanilla Java — a difference in the viewer's renderer, not
     * in the owner being ridden. {@link ConfigManager#getBedrockHeightAdjust()}
     * and {@link ConfigManager#getBedrockSneakHeightAdjust()} are therefore
     * only ever added to the entity a Bedrock viewer is shown, regardless of
     * which platform the owner themselves is on.
     */
    private Transformation buildTransformation(boolean sneaking, boolean forBedrockViewer) {
        double translationY = heightAboveFeet(sneaking, forBedrockViewer) - ASSUMED_MOUNT_OFFSET;
        return new Transformation(
                new Vector3f(0f, (float) translationY, 0f),
                IDENTITY_ROTATION,
                UNIT_SCALE,
                IDENTITY_ROTATION);
    }
}