package cc.kjarni.aetherous.coordfuscation;

public class Offset
{
	Offset(int x, int y)
	{
		X = x;
		Y = y;
		
		teleported = false;
		lastTeleportX = 0.0;
		lastTeleportZ = 0.0;
	}
	
	public int X;
	public int Y;
	
	public boolean teleported;
	public double lastTeleportX;
	public double lastTeleportZ;
}
