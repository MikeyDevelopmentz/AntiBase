package mikey.me.antiBase;

import java.util.UUID;

/** everything the packet threads need, snapshotted on the server thread in one go */
record ViewerState(UUID worldId, int minHeight, boolean protectedWorld,
                   double x, double y, double z, VisibilitySnapshot visibility) {
    ViewerState withVisibility(VisibilitySnapshot next) {
        return new ViewerState(worldId, minHeight, protectedWorld, x, y, z, next);
    }

    boolean shouldHide(int bx, int by, int bz, int hideBelow) {
        return protectedWorld && by < hideBelow && !visibility.isBlockVisible(bx, by, bz);
    }
}
