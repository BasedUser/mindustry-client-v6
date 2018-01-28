package io.anuke.mindustry.ui.dialogs;

import io.anuke.mindustry.Vars;
import io.anuke.mindustry.net.Net;
import io.anuke.ucore.core.Settings;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.util.Bundles;
import io.anuke.ucore.util.Strings;

import java.io.IOException;

import static io.anuke.mindustry.Vars.ui;

//TODO add port specification
public class HostDialog extends FloatingDialog{
    float w = 300;

    public HostDialog(){
        super("$text.hostserver");

        addCloseButton();

        content().table(t -> {
            t.add("$text.name").padRight(10);
            t.addField(Settings.getString("name"), text -> {
                if(text.isEmpty()) return;
                Vars.player.name = text;
                Settings.put("name", text);
                Settings.save();
                ui.listfrag.rebuild();
            }).grow().pad(8);
        }).width(w).height(70f).pad(4);

        content().row();

        content().addButton("$text.host", () -> {
            ui.loadfrag.show("$text.hosting");
            Timers.runTask(5f, () -> {
                try{
                    Net.host(Vars.port);
                }catch (IOException e){
                    ui.showError(Bundles.format("text.server.error", Strings.parseException(e, false)));
                }
                ui.loadfrag.hide();
                hide();
            });
        }).width(w).height(70f);
    }

    /*
    showTextInput("$text.hostserver", "$text.server.port", Vars.port + "", new DigitsOnlyFilter(), text -> {
			int result = Strings.parseInt(text);
			if(result == Integer.MIN_VALUE || result >= 65535){
				ui.showError("$text.server.invalidport");
			}else{
				try{
					Net.host(result);
				}catch (IOException e){
					ui.showError(Bundles.format("text.server.error", Strings.parseException(e, false)));
				}
			}
		});
     */
}
