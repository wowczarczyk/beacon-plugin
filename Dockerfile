# Use the unofficial but popular itzg Minecraft Server image
FROM itzg/minecraft-server:latest

# Switch the server type to Spigot (you can also use Paper by setting TYPE=PAPER)
ENV TYPE=PAPER
ENV EULA=TRUE

# Example: Copy your plugin JAR into the plugins directory
# Replace 'BeaconPairsPlugin.jar' with the name of your JAR
COPY target/BeaconPairsPlugin.jar /plugins/BeaconPairsPlugin.jar