package org.uiutils;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.uiutils.mixin.accessor.ClientConnectionAccessor;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainClient implements ClientModInitializer {
    public static KeyBinding restoreScreenKey;
    public static boolean isMac = false;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            isMac = true;
        }

        // register "restore screen" key
        restoreScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Restore Screen", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "UI Utils"));

        // register event for END_CLIENT_TICK
        ClientTickEvents.END_CLIENT_TICK.register((client) -> {
            // detect if the "restore screen" keybinding is pressed
            while (restoreScreenKey.wasPressed()) {
                if (SharedVariables.storedScreen != null && SharedVariables.storedScreenHandler != null && client.player != null) {
                    client.setScreen(SharedVariables.storedScreen);
                    client.player.currentScreenHandler = SharedVariables.storedScreenHandler;
                }
            }
        });

        // set java.awt.headless to false if os is not mac (allows for jframe guis to be used)
        if (!isMac) {
            System.setProperty("java.awt.headless", "false");

            // set uimanager to system look and feel
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings("all")
    public static void createText(MinecraftClient mc, DrawContext context, TextRenderer textRenderer) {
        // display the current gui's sync id, revision, and credit
        context.drawText(textRenderer, "Sync Id: " + mc.player.currentScreenHandler.syncId, 200, 5, Color.WHITE.getRGB(), false);
        context.drawText(textRenderer, "Revision: " + mc.player.currentScreenHandler.getRevision(), 200, 35, Color.WHITE.getRGB(), false);
        context.drawText(textRenderer, "UI-Utils made by Coderx Gamer.", 10, mc.currentScreen.height - 20, Color.WHITE.getRGB(), false);
    }

    public static void createWidgets(MinecraftClient mc, Screen screen, TextRenderer textRenderer) {
        // register "close without packet" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Close without packet"), (button) -> {
            // closes the current gui without sending a packet to the current server
            mc.setScreen(null);
        }).width(115).position(5, 5).build());

        // register "de-sync" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("De-sync"), (button) -> {
            // keeps the current gui open client-side and closed server-side
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }).width(90).position(5, 35).build());

        // register "send packets" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Send packets: " + SharedVariables.sendUIPackets), (button) -> {
            // tells the client if it should send any gui related packets
            SharedVariables.sendUIPackets = !SharedVariables.sendUIPackets;
            button.setMessage(Text.of("Send packets: " + SharedVariables.sendUIPackets));
        }).width(115).position(5, 65).build());

        // register "delay packets" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Delay packets: " + SharedVariables.delayUIPackets), (button) -> {
            // toggles a setting to delay all gui related packets to be used later when turning this setting off
            SharedVariables.delayUIPackets = !SharedVariables.delayUIPackets;
            button.setMessage(Text.of("Delay packets: " + SharedVariables.delayUIPackets));
            if (!SharedVariables.delayUIPackets && !SharedVariables.delayedUIPackets.isEmpty()) {
                for (Packet<?> packet : SharedVariables.delayedUIPackets) {
                    mc.getNetworkHandler().sendPacket(packet);
                }
                SharedVariables.delayedUIPackets.clear();
            }
        }).width(115).position(5, 95).build());

        // register "save gui" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Save GUI"), (button) -> {
            // saves the current gui to a variable to be accessed later
            SharedVariables.storedScreen = mc.currentScreen;
            SharedVariables.storedScreenHandler = mc.player.currentScreenHandler;
        }).width(115).position(5, 125).build());

        // register "disconnect and send packets" button in all HandledScreens
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Disconnect and send packets"), (button) -> {
            // sends all "delayed" gui related packets before disconnecting, use: potential race conditions on non-vanilla servers
            if (!SharedVariables.delayedUIPackets.isEmpty()) {
                SharedVariables.delayUIPackets = false;
                for (Packet<?> packet : SharedVariables.delayedUIPackets) {
                    mc.getNetworkHandler().sendPacket(packet);
                }
                mc.getNetworkHandler().getConnection().disconnect(Text.of("Disconnecting (UI UTILS)"));
                SharedVariables.delayedUIPackets.clear();
            }
        }).width(160).position(5, 155).build());

        // register "fabricate packet" button in all HandledScreens
        ButtonWidget fabricatePacketButton = ButtonWidget.builder(Text.of("Fabricate packet"), (button) -> {
            // creates a gui allowing you to fabricate packets

            JFrame frame = new JFrame("Choose Packet");

            JButton clickSlotButton = new JButton("Click Slot");
            clickSlotButton.setBounds(100, 25, 110, 20);
            clickSlotButton.setFocusable(false);
            clickSlotButton.addActionListener((event) -> {
                // im too lazy to comment everything here just read the code yourself
                frame.setVisible(false);

                JFrame clickSlotFrame = new JFrame("Click Slot Packet");

                JLabel syncIdLabel = new JLabel("Sync Id:");
                syncIdLabel.setFocusable(false);
                syncIdLabel.setBounds(25, 25, 100, 20);

                JLabel revisionLabel = new JLabel("Revision:");
                revisionLabel.setFocusable(false);
                revisionLabel.setBounds(25, 50, 100, 20);

                JLabel slotLabel = new JLabel("Slot:");
                slotLabel.setFocusable(false);
                slotLabel.setBounds(25, 75, 100, 20);

                JLabel buttonLabel = new JLabel("Button:");
                buttonLabel.setFocusable(false);
                buttonLabel.setBounds(25, 100, 100, 20);

                JLabel actionLabel = new JLabel("Action:");
                actionLabel.setFocusable(false);
                actionLabel.setBounds(25, 125, 100, 20);

                JTextField syncIdField = new JTextField(1);
                syncIdField.setBounds(125, 25, 100, 20);

                JTextField revisionField = new JTextField(1);
                revisionField.setBounds(125, 50, 100, 20);

                JTextField slotField = new JTextField(1);
                slotField.setBounds(125, 75, 100, 20);

                JTextField buttonField = new JTextField(1);
                buttonField.setBounds(125, 100, 100, 20);

                JComboBox<String> actionField = new JComboBox<>();
                List<String> actions = ImmutableList.of(
                        "PICKUP",
                        "QUICK_MOVE",
                        "SWAP",
                        "CLONE",
                        "THROW",
                        "QUICK_CRAFT",
                        "PICKUP_ALL"
                );
                actionField.setEditable(false);
                actionField.setFocusable(false);
                actionField.setBounds(125, 125, 100, 20);
                for (String action : actions) {
                    actionField.addItem(action);
                }

                JLabel statusLabel = new JLabel();
                statusLabel.setForeground(Color.WHITE);
                statusLabel.setFocusable(false);
                statusLabel.setBounds(185, 150, 190, 20);

                JCheckBox delayBox = new JCheckBox("Delay");
                delayBox.setBounds(115, 150, 85, 20);
                delayBox.setSelected(false);
                delayBox.setFocusable(false);

                JButton sendButton = new JButton("Send");
                sendButton.setFocusable(false);
                sendButton.setBounds(25, 150, 75, 20);
                sendButton.addActionListener((event0) -> {
                    if (
                            syncIdField.getText().isEmpty() ||
                                    revisionField.getText().isEmpty() ||
                                    slotField.getText().isEmpty() ||
                                    buttonField.getText().isEmpty()) {
                        statusLabel.setForeground(Color.RED.darker());
                        statusLabel.setText("Invalid arguments!");
                        MainClient.queueTask(() -> {
                            statusLabel.setForeground(Color.WHITE);
                            statusLabel.setText("");
                        }, 1500L);
                        return;
                    }
                    if (
                            MainClient.isInteger(syncIdField.getText()) &&
                                    MainClient.isInteger(revisionField.getText()) &&
                                    MainClient.isInteger(slotField.getText()) &&
                                    MainClient.isInteger(buttonField.getText()) &&
                                    actionField.getSelectedItem() != null) {
                        int syncId = Integer.parseInt(syncIdField.getText());
                        int revision = Integer.parseInt(revisionField.getText());
                        int slot = Integer.parseInt(slotField.getText());
                        int button0 = Integer.parseInt(buttonField.getText());
                        SlotActionType action = MainClient.stringToSlotActionType(actionField.getSelectedItem().toString());

                        if (action != null) {
                            ClickSlotC2SPacket packet = new ClickSlotC2SPacket(syncId, revision, slot, button0, action, ItemStack.EMPTY, new Int2ObjectArrayMap<>());
                            try {
                                if (delayBox.isSelected()) {
                                    mc.getNetworkHandler().sendPacket(packet);
                                } else {
                                    ((ClientConnectionAccessor) mc.getNetworkHandler().getConnection()).getChannel().writeAndFlush(packet);
                                }
                            } catch (Exception e) {
                                statusLabel.setForeground(Color.RED.darker());
                                statusLabel.setText("You must be connected to a server!");
                                MainClient.queueTask(() -> {
                                    statusLabel.setForeground(Color.WHITE);
                                    statusLabel.setText("");
                                }, 1500L);
                                return;
                            }
                            statusLabel.setForeground(Color.GREEN.darker());
                            statusLabel.setText("Sent successfully!");
                            MainClient.queueTask(() -> {
                                statusLabel.setForeground(Color.WHITE);
                                statusLabel.setText("");
                            }, 1500L);
                        } else {
                            statusLabel.setForeground(Color.RED.darker());
                            statusLabel.setText("Invalid arguments!");
                            MainClient.queueTask(() -> {
                                statusLabel.setForeground(Color.WHITE);
                                statusLabel.setText("");
                            }, 1500L);
                        }
                    } else {
                        statusLabel.setForeground(Color.RED.darker());
                        statusLabel.setText("Invalid arguments!");
                        MainClient.queueTask(() -> {
                            statusLabel.setForeground(Color.WHITE);
                            statusLabel.setText("");
                        }, 1500L);
                    }
                });

                clickSlotFrame.setBounds(0, 0, 450, 250);
                clickSlotFrame.setLayout(null);
                clickSlotFrame.setLocationRelativeTo(null);
                clickSlotFrame.add(syncIdLabel);
                clickSlotFrame.add(revisionLabel);
                clickSlotFrame.add(slotLabel);
                clickSlotFrame.add(buttonLabel);
                clickSlotFrame.add(actionLabel);
                clickSlotFrame.add(syncIdField);
                clickSlotFrame.add(revisionField);
                clickSlotFrame.add(slotField);
                clickSlotFrame.add(buttonField);
                clickSlotFrame.add(actionField);
                clickSlotFrame.add(sendButton);
                clickSlotFrame.add(statusLabel);
                clickSlotFrame.add(delayBox);
                clickSlotFrame.setVisible(true);
            });

            JButton buttonClickButton = new JButton("Button Click");
            buttonClickButton.setBounds(220, 25, 110, 20);
            buttonClickButton.setFocusable(false);
            buttonClickButton.addActionListener((event) -> {
                frame.setVisible(false);

                JFrame buttonClickFrame = new JFrame("Button Click Packet");

                JLabel syncIdLabel = new JLabel("Sync Id:");
                syncIdLabel.setFocusable(false);
                syncIdLabel.setBounds(25, 25, 100, 20);

                JLabel buttonIdLabel = new JLabel("Button Id:");
                buttonIdLabel.setFocusable(false);
                buttonIdLabel.setBounds(25, 50, 100, 20);

                JTextField syncIdField = new JTextField(1);
                syncIdField.setBounds(125, 25, 100, 20);

                JTextField buttonIdField = new JTextField(1);
                buttonIdField.setBounds(125, 50, 100, 20);

                JLabel statusLabel = new JLabel();
                statusLabel.setForeground(Color.WHITE);
                statusLabel.setFocusable(false);
                statusLabel.setBounds(185, 95, 190, 20);

                JCheckBox delayBox = new JCheckBox("Delay");
                delayBox.setBounds(115, 95, 85, 20);
                delayBox.setSelected(false);
                delayBox.setFocusable(false);

                JButton sendButton = new JButton("Send");
                sendButton.setFocusable(false);
                sendButton.setBounds(25, 95, 75, 20);
                sendButton.addActionListener((event0) -> {
                    if (syncIdField.getText().isEmpty() || buttonIdField.getText().isEmpty()) {
                        statusLabel.setForeground(Color.RED.darker());
                        statusLabel.setText("Invalid arguments!");
                        MainClient.queueTask(() -> {
                            statusLabel.setForeground(Color.WHITE);
                            statusLabel.setText("");
                        }, 1500L);
                        return;
                    }
                    if (MainClient.isInteger(syncIdField.getText()) && MainClient.isInteger(buttonIdField.getText())) {
                        int syncId = Integer.parseInt(syncIdField.getText());
                        int buttonId = Integer.parseInt(buttonIdField.getText());

                        ButtonClickC2SPacket packet = new ButtonClickC2SPacket(syncId, buttonId);
                        try {
                            if (delayBox.isSelected()) {
                                mc.getNetworkHandler().sendPacket(packet);
                            } else {
                                ((ClientConnectionAccessor) mc.getNetworkHandler().getConnection()).getChannel().writeAndFlush(packet);
                            }
                        } catch (Exception e) {
                            statusLabel.setForeground(Color.RED.darker());
                            statusLabel.setText("You must be connected to a server!");
                            MainClient.queueTask(() -> {
                                statusLabel.setForeground(Color.WHITE);
                                statusLabel.setText("");
                            }, 1500L);
                            return;
                        }
                        statusLabel.setForeground(Color.GREEN.darker());
                        statusLabel.setText("Sent successfully!");
                        MainClient.queueTask(() -> {
                            statusLabel.setForeground(Color.WHITE);
                            statusLabel.setText("");
                        }, 1500L);
                    } else {
                        statusLabel.setForeground(Color.RED.darker());
                        statusLabel.setText("Invalid arguments!");
                        MainClient.queueTask(() -> {
                            statusLabel.setForeground(Color.WHITE);
                            statusLabel.setText("");
                        }, 1500L);
                    }
                });

                buttonClickFrame.setBounds(0, 0, 450, 180);
                buttonClickFrame.setLayout(null);
                buttonClickFrame.setLocationRelativeTo(null);
                buttonClickFrame.add(syncIdLabel);
                buttonClickFrame.add(buttonIdLabel);
                buttonClickFrame.add(syncIdField);
                buttonClickFrame.add(buttonIdField);
                buttonClickFrame.add(sendButton);
                buttonClickFrame.add(statusLabel);
                buttonClickFrame.add(delayBox);
                buttonClickFrame.setVisible(true);
            });

            frame.setBounds(0, 0, 450, 100);
            frame.setLayout(null);
            frame.setLocationRelativeTo(null);
            frame.add(clickSlotButton);
            frame.add(buttonClickButton);
            frame.setVisible(true);
        }).width(115).position(5, 185).build();
        fabricatePacketButton.active = !isMac;
        screen.addDrawableChild(fabricatePacketButton);

        screen.addDrawableChild(ButtonWidget.builder(Text.of("Copy GUI Title JSON"), (button) -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Text.Serializer.toJson(mc.currentScreen.getTitle())), null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).width(115).position(5, 215).build());
    }

    public static boolean isInteger(String string) {
        try {
            Integer.parseInt(string);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static SlotActionType stringToSlotActionType(String string) {
        // converts a string to SlotActionType
        return switch (string) {
            case "PICKUP" -> SlotActionType.PICKUP;
            case "QUICK_MOVE" -> SlotActionType.QUICK_MOVE;
            case "SWAP" -> SlotActionType.SWAP;
            case "CLONE" -> SlotActionType.CLONE;
            case "THROW" -> SlotActionType.THROW;
            case "QUICK_CRAFT" -> SlotActionType.QUICK_CRAFT;
            case "PICKUP_ALL" -> SlotActionType.PICKUP_ALL;
            default -> null;
        };
    }

    public static void queueTask(Runnable runnable, long delayMs) {
        // queues a task for minecraft to run
        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                MinecraftClient.getInstance().send(runnable);
                timer.purge();
                timer.cancel();
            }
        };
        timer.schedule(task, delayMs);
    }
}
