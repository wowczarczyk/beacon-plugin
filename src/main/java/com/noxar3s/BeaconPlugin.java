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

import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.util.*;

public class BeaconPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, UUID> teamPartners = new HashMap<>();
    private final Map<UUID, Location> teamBeaconLocation = new HashMap<>();
    private boolean canPvP = false;
    private boolean beaconGiven = false;

    private final Set<UUID> teamsPlacedBeacon = new HashSet<>(); // Track teams that placed a beacon
    private ScoreboardManager scoreboardManager;
    private Objective timerObjective;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Register commands
        if (this.getCommand("startgame") != null) {
            Objects.requireNonNull(this.getCommand("startgame")).setExecutor(this);
        }
        if (this.getCommand("giveweapons") != null) {
            Objects.requireNonNull(this.getCommand("giveweapons")).setExecutor(this);
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
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (event.getBlock().getType() == Material.BEACON) {
            UUID teamId = getTeamId(player.getUniqueId());
            if (!beaconGiven || teamsPlacedBeacon.contains(teamId)) {
                event.setCancelled(true);
                return;
            }
            // Check if the player is holding the beacon given by the plugin
            ItemStack item = event.getItemInHand();
            if (item.getType() == Material.BEACON) {
                teamsPlacedBeacon.add(teamId);
                registerBeaconPlacement(player.getUniqueId(), event.getBlock().getLocation());
                player.sendMessage(ChatColor.GREEN + "Beacon placed! Protect it!");
                // Remove the beacon from inventory
                item.setAmount(item.getAmount() - 1);
            }
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

    private void startTimerScoreboard() {
        scoreboardManager = Bukkit.getScoreboardManager();
        Scoreboard board = scoreboardManager.getNewScoreboard();
        timerObjective = board.registerNewObjective("timer", "dummy", ChatColor.BOLD + "Time Remaining");
        timerObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        new BukkitRunnable() {
            int timeLeft = 20 * 60; // 20 minutes in seconds

            @Override
            public void run() {
                if (timeLeft <= 0) {
                    cancel();
                    return;
                }
                timerObjective.getScore("Time: ").setScore(timeLeft);
                timeLeft--;
            }
        }.runTaskTimer(this, 0, 20); // Update every second
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
        } else if (label.equalsIgnoreCase("giveweapons")) {
            if (!sender.hasPermission("beaconplugin.giveweapons")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                return true;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                giveRandomWeapon(player);
            }
            sender.sendMessage(ChatColor.GREEN + "Random weapons distributed to all players!");
            return true;
        }
        return false;
    }

    private void giveRandomWeapon(Player player) {
        Material[] weapons = {
                Material.WOODEN_SWORD,
                Material.STONE_SWORD,
                Material.IRON_SWORD,
                Material.DIAMOND_SWORD,
                Material.NETHERITE_SWORD,
                Material.BOW,
                Material.CROSSBOW
        };

        int randomIndex = new Random().nextInt(weapons.length);
        ItemStack weapon = new ItemStack(weapons[randomIndex]);
        player.getInventory().addItem(weapon);
        player.sendMessage(ChatColor.GREEN + "You received a "
                + weapon.getType().toString().toLowerCase().replace('_', ' ') + "!");
    }

    private void assignToATeam(Player player) {
        List<UUID> waitingPlayers = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : teamPartners.entrySet()) {
            if (entry.getValue() == null) {
                waitingPlayers.add(entry.getKey());
            }
        }

        if (!waitingPlayers.isEmpty()) {
            UUID partnerId = waitingPlayers.get(0);
            teamPartners.put(partnerId, player.getUniqueId());
            teamPartners.put(player.getUniqueId(), partnerId);
            Player partner = Bukkit.getPlayer(partnerId);
            if (partner != null) {
                partner.sendMessage(ChatColor.GREEN + "You've been paired with " + player.getName() + "!");
                player.sendMessage(ChatColor.GREEN + "You've been paired with " + partner.getName() + "!");
            }
        } else {
            teamPartners.put(player.getUniqueId(), null);
            player.sendMessage(ChatColor.YELLOW + "Waiting for a partner...");
        }
    }

    private void startGame(World world) {
        // Check all teams are paired
        for (UUID playerId : teamPartners.keySet()) {
            if (teamPartners.get(playerId) == null) {
                Bukkit.broadcastMessage(ChatColor.RED + "Cannot start: Unpaired players!");
                return;
            }
        }

        Set<UUID> processedTeams = new HashSet<>();
        for (UUID playerId : teamPartners.keySet()) {
            UUID teamId = getTeamId(playerId);
            if (!processedTeams.contains(teamId)) {
                processedTeams.add(teamId);
                UUID partnerId = teamPartners.get(playerId);
                Player player1 = Bukkit.getPlayer(playerId);
                Player player2 = Bukkit.getPlayer(partnerId);
                if (player1 != null && player2 != null) {
                    Location randomLoc = generateRandomLocation(world, 10000, 10000, 500);
                    player1.teleport(randomLoc);
                    player2.teleport(randomLoc.add(2, 0, 0)); // Offset to prevent suffocation
                }
            }
        }

        Bukkit.broadcastMessage(ChatColor.YELLOW + "Teams have been teleported. Grace period begins!");
        canPvP = false;
        beaconGiven = false;

        // Start timers and scoreboard
        startTimerScoreboard();

        // 5-minute PvP/beacon timer
        getServer().getScheduler().runTaskLater(this, () -> {
            canPvP = true;
            beaconGiven = true;
            giveBeaconsToTeams();
            Bukkit.broadcastMessage(ChatColor.RED + "PvP is now enabled! You have 15 minutes to place your beacons.");
        }, 5L * 60L * 20L);

        // 20-minute disqualification timer
        getServer().getScheduler().runTaskLater(this, () -> {
            disqualifyTeamsWithoutBeacon();
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Time is up! Teams without placed beacons are disqualified.");
        }, (5L + 15L) * 60L * 20L);
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
        Set<UUID> disqualifiedTeams = new HashSet<>();
        for (UUID playerId : teamPartners.keySet()) {
            UUID teamId = getTeamId(playerId);
            if (!teamsPlacedBeacon.contains(teamId)) {
                disqualifiedTeams.add(teamId);
            }
        }

        for (UUID teamId : disqualifiedTeams) {
            for (UUID playerId : teamPartners.keySet()) {
                if (getTeamId(playerId).equals(teamId)) {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null) {
                        p.kickPlayer(ChatColor.RED + "No beacon placed. Disqualified!");
                    }
                }
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