package com.npcpermadeath;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class NpcPermadeathPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(NpcPermadeathPlugin.class);
		RuneLite.main(args);
	}
}
