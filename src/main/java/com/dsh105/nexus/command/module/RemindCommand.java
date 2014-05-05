/*
 * This file is part of Nexus.
 *
 * Nexus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nexus.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dsh105.nexus.command.module;

import com.dsh105.nexus.Nexus;
import com.dsh105.nexus.command.Command;
import com.dsh105.nexus.command.CommandModule;
import com.dsh105.nexus.command.CommandPerformEvent;
import com.dsh105.nexus.util.StringUtil;
import com.dsh105.nexus.util.TimeUtil;
import org.pircbotx.Channel;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;

@Command(command = "remind", help = "Schedule a reminder",
        extendedHelp = {
                "Schedules a reminder to be executed after the specified time period.",
                "Valid time periods are: {b}s{/b} (seconds), {b}m{/b} (minutes), {b}h{/b} (hours), {b}d{/b} (days), {b}w{/b} (weeks)",
                "Examples: 1d (1 day), 2h (2 hours), 5m30s (5 minutes, 30 seconds), 1w3d (1 week, 3 days)",
                "--------",
                "Command syntax:",
                "{b}{p}{c} <time_period> <reminder>{/b} - schedules a reminder with the given message. The {b}<reminder>{/b} message can be more than one word.",
                "{b}{p}{c} <user_to_remind> <time_period> <reminder>{/b} - schedules a reminder with the given message for a user. The {b}<reminder>{/b} message can be more than one word."})
public class RemindCommand extends CommandModule {

    private ArrayList<Reminder> reminders = new ArrayList<>();

    @Override
    public boolean onCommand(CommandPerformEvent event) {
        if (event.getArgs().length >= 2) {
            long timePeriod = -1;
            boolean forOtherUser = false;
            try {
                timePeriod = TimeUtil.parse(event.getArgs()[1]);
                forOtherUser = true;
            } catch (NumberFormatException e) {
            }

            String userToRemind = forOtherUser ? event.getArgs()[0] : event.getSender().getNick();

            if (!forOtherUser) {
                try {
                    timePeriod = TimeUtil.parse(event.getArgs()[0]);
                } catch (NumberFormatException e) {
                }
            }
            if (timePeriod <= 0) {
                event.respondWithPing("Invalid time period entered: {0}. Examples: {1} (1 day), {2} (2 hours), {3} (5 minutes, 30 seconds), {4} (1 week, 3 days)", event.getArgs()[0], "1d", "2h", "5m30s", "1w3d");
                return true;
            }
            String reminderMessage = StringUtil.combineSplit(forOtherUser ? 2 : 1, event.getArgs(), " ");
            Reminder reminder = new Reminder(event.getChannel(), userToRemind, event.getSender().getNick(), reminderMessage);;
            new Timer(true).schedule(reminder, timePeriod);
            reminders.add(reminder);
            event.respondWithPing("Reminder scheduled for {0}", event.getArgs()[forOtherUser ? 1 : 0]);
            return true;
        }
        return false;
    }

    public ArrayList<Reminder> getReminders() {
        return new ArrayList<>(reminders);
    }

    public void saveReminders() {
        int index = 0;
        for (Reminder r : reminders) {
            File remindersFolder = new File("reminders");
            if (!remindersFolder.exists()) {
                remindersFolder.mkdirs();
            }
            try {
                File file = new File(remindersFolder, "reminders-" + index + ".txt");
                if (file.exists()) {
                    file.delete();
                }
                file.createNewFile();

                HashMap<String, Object> valueMap = new HashMap<>();
                valueMap.put("channel", r.channel.getName());
                valueMap.put("user", r.userToRemind);
                valueMap.put("from", r.from);
                valueMap.put("reminder", r.reminder);
                valueMap.put("execution_time", r.scheduledExecutionTime());

                PrintWriter writer = new PrintWriter(new FileOutputStream(file));
                Yaml yaml = new Yaml();
                writer.write(yaml.dump(valueMap));
                writer.close();
            } catch (IOException e) {
                Nexus.LOGGER.severe("Could not save reminders!");
                e.printStackTrace();
            }
        }
        this.cancelReminders();
    }

    public void loadReminders() {
        try {
            File remindersFolder = new File("reminders");
            if (!remindersFolder.exists()) {
                remindersFolder.mkdirs();
            }
            ArrayList<File> toRemove = new ArrayList<>();
            for (File f : remindersFolder.listFiles()) {
                if (f.getName().startsWith("reminders-")) {
                    FileInputStream input = new FileInputStream(f);
                    Yaml yaml = new Yaml();
                    Map<String, Object> data = (Map<String, Object>) yaml.load(input);
                    if (data != null && !data.isEmpty()) {
                        try {
                            Reminder reminder = new Reminder(Nexus.getInstance().getChannel((String) data.get("channel")), (String) data.get("user"), (String) data.get("from"), (String) data.get("reminder"));
                            long executionTime = (Long) data.get("execution_time");
                            Date current = new Date();
                            Date execution = new Date(executionTime);
                            if (execution.after(current)) {
                                // Yay, we can still schedule this timer
                                new Timer(true).schedule(reminder, execution.getTime() - current.getTime());
                                reminders.add(reminder);
                            }
                        } catch (Exception e) {
                        }
                    }
                    toRemove.add(f);
                }
            }

            Iterator<File> i = toRemove.iterator();
            while (i.hasNext()) {
                File f = i.next();
                f.delete();
                i.remove();
            }
        } catch (IOException e) {
            Nexus.LOGGER.severe("Could not load reminders!");
            e.printStackTrace();
        }
    }

    public void cancelReminders() {
        Iterator<Reminder> i = reminders.iterator();
        while (i.hasNext()) {
            i.next().cancel();
            i.remove();
        }
    }

    public class Reminder extends TimerTask {

        private Channel channel;
        private String userToRemind;
        private String from;
        private String reminder;

        public Reminder(Channel channel, String userToRemind, String from, String reminder) {
            this.channel = channel;
            this.userToRemind = userToRemind;
            this.from = from == null ? userToRemind : from;
            this.reminder = reminder;
        }

        @Override
        public void run() {
            if (channel != null) {
                Nexus.getInstance().sendAction(channel, "reminds " + userToRemind + " to " + reminder + StringUtil.removePing(from.equals(userToRemind) ? "" : " (from " + from + ")"));
            }
            this.cancel(true);
        }

        public void cancel(boolean remove) {
            if (remove) {
                reminders.remove(this);
            }
            super.cancel();
        }
    }
}