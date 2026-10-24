package me.aap.fermata.vfs;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.vfs.m3u.M3uFileSystem;
import me.aap.fermata.vfs.m3u.M3uFileSystemProvider;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.vfs.VfsManager;
import me.aap.utils.vfs.VirtualFileSystem;
import me.aap.utils.vfs.content.ContentFileSystem;
import me.aap.utils.vfs.generic.GenericFileSystem;
import me.aap.utils.vfs.local.LocalFileSystem;

/**
 * @author Andrey Pavlenko
 */
public class FermataVfsManager extends VfsManager {
	public static final String M3U_ID = "m3u";

	public FermataVfsManager() {
		super(filesystems());
	}

	public FutureSupplier<VfsProvider> getProvider(String scheme) {
		if (M3U_ID.equals(scheme)) return completed(new M3uFileSystemProvider());
		return completedNull();
	}

	private static List<VirtualFileSystem> filesystems() {
		FermataApplication app = FermataApplication.get();
		PreferenceStore ps = app.getPreferenceStore();
		List<VirtualFileSystem> p = new ArrayList<>(4);
		p.add(LocalFileSystem.Provider.getInstance().createFileSystem(ps).getOrThrow());
		p.add(GenericFileSystem.Provider.getInstance().createFileSystem(ps).getOrThrow());
		p.add(ContentFileSystem.Provider.getInstance().createFileSystem(ps).getOrThrow());
		p.add(M3uFileSystem.Provider.getInstance().createFileSystem(ps).getOrThrow());
		return p;
	}
}
