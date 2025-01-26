package com.noxar3s;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class BeaconPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, UUID> teamPartners = new HashMap<>();
    private final Map<UUID, Location> teamBeaconLocation = new HashMap<>();
    private boolean canPvP = false;
    private boolean beaconGiven = false;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Safely register the command
        if (this.getCommand("startgame") != null) {
            Objects.requireNonNull(this.getCommand("startgame")).setExecutor(this);
        } else {
            getLogger().severe("Command 'startgame' not found in plugin.yml. Plugin may not function correctly!");
        }

        getLogger().info("BeaconPairsPlugin (Paper) enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("BeaconPairsPlugin (Paper) disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Assign player to a team if they don't have a partner
        if (!teamPartners.containsKey(player.getUniqueId())) {
            assignToATeam(player);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Manage no-PvP grace period
        if (!canPvP && event.getDamager() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // If a beacon is broken, check if it's a team's beacon
        if (event.getBlock().getType() == Material.BEACON) {
            Player breaker = event.getPlayer();
            Location loc = event.getBlock().getLocation();

            UUID victimTeam = getBeaconOwner(loc);
            if (victimTeam != null) {
                // Inform the team they're vulnerable now
                for (UUID partnerId : teamPartners.keySet()) {
                    if (isSameTeam(partnerId, victimTeam)) {
                        Player partnerPlayer = Bukkit.getPlayer(partnerId);
                        if (partnerPlayer != null) {
                            partnerPlayer.sendMessage(ChatColor.RED + "Your team's beacon was destroyed!");
                        }
                    }
                }
                // Remove the beacon location to indicate it's destroyed
                teamBeaconLocation.remove(victimTeam);
            }
        }
    }

    // --------------------------------------------------------------------------
    // Command: /startgame
    // --------------------------------------------------------------------------
    // Teleports teams randomly, starts a 5-minute no-PvP countdown, then
    // distributes beacons. After 20 minutes total, disqualifies teams that
    // haven't placed their beacon.
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }
        if (label.equalsIgnoreCase("startgame")) {
            Player p = (Player) sender;
            startGame(p.getWorld());
            return true;
        }
        return false;
    }

    // --------------------------------------------------------------------------
    // Logic for team assignments, game timers, giving beacons, etc.
    // --------------------------------------------------------------------------
    private void assignToATeam(Player player) {
        // Try to pair them with someone who doesn't have a partner yet
        for (Map.Entry<UUID, UUID> entry : teamPartners.entrySet()) {
            if (entry.getValue() == null) {
                teamPartners.put(entry.getKey(), player.getUniqueId());
                teamPartners.put(player.getUniqueId(), entry.getKey());
                player.sendMessage(ChatColor.GREEN + "You joined a team with an existing player!");
                return;
            }
        }
        // If no existing partial team, create a new partial team
        teamPartners.put(player.getUniqueId(), null);
        player.sendMessage(ChatColor.GREEN + "Waiting for another player to join your team...");
    }

    private void startGame(World world) {
        // Example random teleport for each complete team
        for (UUID playerId : teamPartners.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                if (teamPartners.get(playerId) != null) {
                    // Teleport each player to a random location within 1000 blocks of some center
                    Location randomLoc = generateRandomLocation(world, 10000, 10000, 500);
                    player.teleport(randomLoc);
                }
            }
        }

        Bukkit.broadcastMessage(ChatColor.YELLOW + "Teams have been teleported. Grace period begins!");
        canPvP = false;
        beaconGiven = false;

        // 1. After 5 minutes, allow PvP and give beacons
        getServer().getScheduler().runTaskLater(this, () -> {
            canPvP = true;
            beaconGiven = true;
            giveBeaconsToTeams();
            Bukkit.broadcastMessage(ChatColor.RED + "PvP is now enabled! You have 15 minutes to place your beacons.");
        }, 5L * 60L * 20L); // 5 minutes in ticks

        // 2. After a total of 20 minutes, disqualify teams without a beacon
        getServer().getScheduler().runTaskLater(this, () -> {
            disqualifyTeamsWithoutBeacon();
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Time is up! Teams without placed beacons are disqualified.");
        }, (5L + 15L) * 60L * 20L); // 20 minutes total in ticks
    }

    private void giveBeaconsToTeams() {
        // Each pair gets exactly one beacon
        Set<UUID> processedTeams = new HashSet<>();
        for (UUID playerId : teamPartners.keySet()) {
            UUID teamId = getTeamId(playerId);
            if (!processedTeams.contains(teamId)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.getInventory().addItem(new ItemStack(Material.BEACON, 1));
                    processedTeams.add(teamId);
                }
            }
        }
    }

    private void disqualifyTeamsWithoutBeacon() {
        // Disqualify (for example, kick) teams that never placed a beacon
        List<UUID> toDisqualify = new ArrayList<>();

        for (UUID playerId : teamPartners.keySet()) {
            UUID teamId = getTeamId(playerId);
            if (!teamBeaconLocation.containsKey(teamId)) {
                toDisqualify.add(playerId);
            }
        }

        for (UUID d : toDisqualify) {
            Player p = Bukkit.getPlayer(d);
            if (p != null) {
                p.sendMessage(ChatColor.RED + "Your team did not place a beacon. You are disqualified!");
                p.kickPlayer("Disqualified (No beacon placed)");
            }
        }
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------
    private Location generateRandomLocation(World world, int centerX, int centerZ, int radius) {
        Random rand = new Random();
        double angle = rand.nextDouble() * 2.0 * Math.PI;
        double r = radius * Math.sqrt(rand.nextDouble());
        int x = (int) Math.round(centerX + r * Math.cos(angle));
        int z = (int) Math.round(centerZ + r * Math.sin(angle));
        int y = world.getHighestBlockYAt(x, z);
        return new Location(world, x, y, z);
    }

    private UUID getBeaconOwner(Location location) {
        for (Map.Entry<UUID, Location> entry : teamBeaconLocation.entrySet()) {
            Location beaconLoc = entry.getValue();
            if (beaconLoc != null && beaconLoc.getWorld().equals(location.getWorld())) {
                if (beaconLoc.getBlockX() == location.getBlockX() &&
                        beaconLoc.getBlockY() == location.getBlockY() &&
                        beaconLoc.getBlockZ() == location.getBlockZ()) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private boolean isSameTeam(UUID p1, UUID p2) {
        return getTeamId(p1).equals(getTeamId(p2));
    }

    // Derive a team ID based on the smaller UUID in a pair
    private UUID getTeamId(UUID playerId) {
        UUID partnerId = teamPartners.get(playerId);
        if (partnerId == null) {
            return playerId;
        }
        return playerId.compareTo(partnerId) < 0 ? playerId : partnerId;
    }

    // Register a beacon’s location when a player places it
    public void registerBeaconPlacement(UUID playerId, Location location) {
        teamBeaconLocation.put(getTeamId(playerId), location);
    }
}