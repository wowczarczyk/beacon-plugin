# Use the unofficial but popular itzg Minecraft Server image
FROM itzg/minecraft-server:latest

# Switch the server type to Spigot (you can also use Paper by setting TYPE=PAPER)
ENV TYPE=PAPER
ENV EULA=TRUE
# Set specific Minecraft version
ENV VERSION=1.20.4
# Add your Minecraft username to ops list
ENV OPS=NoXAr3s

# Example: Copy your plugin JAR into the plugins directory
# Replace 'BeaconPairsPlugin.jar' with the name of your JAR
# COPY target/BeaconPairsPlugin.jar /plugins/BeaconPairsPlugin.jar
COPY AwakenSMPOnline-1.6.4.jar /plugins/AwakenSMPOnline.jar