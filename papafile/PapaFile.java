/*
 *  This file is part of PapaFile
 * 
 *  File IO tools for Planetary Annihilation's papa files.
 *  Copyright (C) 2020 Marcus Der <marcusder@hotmail.com>
 * 
 *  PapaFile is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  PapaFile is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with PapaFile.  If not, see <https://www.gnu.org/licenses/>.
 */
package papafile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import javax.imageio.ImageIO;

import com.github.memo33.jsquish.Squish.CompressionMethod;

import papafile.PapaTexture.ImmutableTextureSettings;
import papafile.PapaTexture.TextureSettings;

public class PapaFile extends PapaComponent{
	
	private long 	fileSize;

	private ArrayList<PapaString> strings = new ArrayList<PapaString>();
	private ArrayList<PapaTexture> textures = new ArrayList<PapaTexture>();
	//private ArrayList<PapaVBuffer> vBuffers = new ArrayList<PapaVBuffer>();
	//private ArrayList<PapaIBuffer> iBuffers = new ArrayList<PapaIBuffer>();
	//private ArrayList<PapaMaterial> materials = new ArrayList<PapaMaterial>();
	//private ArrayList<PapaMesh> meshes = new ArrayList<PapaMesh>();
	//private ArrayList<PapaSkeleton> skeletons = new ArrayList<PapaSkeleton>();
	//private ArrayList<PapaModel> models = new ArrayList<PapaModel>();
	//private ArrayList<PapaAnimation> animations = new ArrayList<PapaAnimation>();
	
	private HashMap<String, PapaFile> linkedFiles = new HashMap<String, PapaFile>();
	
	private byte[] fileBytes = null;
	
	private static final boolean ERROR_IF_NOT_FOUND = false;
	
	@SuppressWarnings("unchecked")
	private ArrayList<? extends PapaComponent>[] components = (ArrayList<? extends PapaComponent>[]) new ArrayList<?>[] {strings,textures};
	
	public static final int NONE =		0b000000000;
	public static final int TEXTURE = 	0b000000001;
	public static final int VBUF = 		0b000000010;
	public static final int IBUF = 		0b000000100;
	public static final int MATERIAL = 	0b000001000;
	public static final int MESH = 		0b000010000;
	public static final int SKELETON = 	0b000100000;
	public static final int MODEL = 	0b001000000;
	public static final int ANIMATION = 0b010000000;
	public static final int STIRNG = 	0b100000000;
	public static final int ALL = 		0b111111111;
	private static final int HEADER_SIZE = 0x68;
	
	private static File PA_ROOT_DIR = null;
	private static String PA_ROOT_DIR_STRING = null;
	
	private int 	signature;
	private int 	minorVersion = 0;
	private int 	majorVersion = 3;
	
	private short	numStrings;
	private short 	numTextures;
	private short 	numVBuffers;
	private short 	numIBuffers;
	
	private short 	numMaterials;
	private short 	numMeshes;
	private short 	numSkeletons;
	private short	numModels;
	
	
	private short 	numAnimations;
	
	private long 	offsetStringTable;
	private long 	offsetTextureTable;
	private long 	offsetVBufferTable;
	private long 	offsetIBufferTable;
	private long 	offsetMaterialTable;
	private long 	offsetMeshTable;
	private long 	offsetSkeletonTable;
	private long 	offsetModelTable;
	private long 	offsetAnimationTable;
	
	
	private File fileLocation;
	private String 	fileName = "Unknown";
	private String 	filePath = "Unknown";
	private String	relativePath = "Unknown";
	
	private boolean isPapa;
	
	private boolean isLinked = false;
	private PapaFile parentFile = null;
	
	private static final PapaFile DEFAULT = new PapaFile();
	
	static {
		try {
			DEFAULT.addTexture(ImageIO.read(PapaFile.class.getResource("/papafile/error.png")), 
								new TextureSettings("R8G8B8A8",CompressionMethod.CLUSTER_FIT, false, 0, false, 0, 0, false,false,null,0).immutable());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private RandomAccessFile in;
	
	private static PapaFile readLinkedFile(String fullPath, PapaFile parent) throws IOException{
		PapaFile p = new PapaFile(fullPath);
		p.attach(parent);
		return p;
	}

	public PapaFile() {};
	
	public PapaFile(PapaComponent... comp) {
		for(PapaComponent c : comp) {
			Class<?> cl = c.getClass();
			if(cl.equals(PapaTexture.class)) { // adding strings is illegal since they are generated on build.
				textures.add((PapaTexture) c);
				numTextures++;
			} else {
				throw new IllegalArgumentException("Unsupported class: "+cl.getSimpleName());
			}
		}
		validateAll();
		fileSize = calcFileSize();
	}
	
	public PapaFile(String path, int flags) throws IOException {
		instantiate(path, flags);
	}
	
	public PapaFile(String path) throws IOException {
		instantiate(path, ALL);
	}
	
	public void setFileLocation(File newLocation) {
		
		if(newLocation == null) {
			fileLocation = null;
			fileName = "Unknown";
			filePath = "Unknown";
			relativePath = "Unknown";
			return;
		}
		
		String path;
		try {
			path = newLocation.getCanonicalPath().replace('\\', '/');  // PA only uses /
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
		int loc = path.lastIndexOf('/');
		fileLocation = newLocation;
		fileName = loc != -1 ? path.substring(loc + 1) : path;
		filePath = loc != -1 ? path.substring(0,loc) : path;
		loc = fileName.lastIndexOf(".");
		isPapa = fileName.substring(loc).equals(".papa");
		if(PA_ROOT_DIR != null && path.startsWith(PA_ROOT_DIR_STRING))
			relativePath = path.substring(PA_ROOT_DIR_STRING.length());
		else
			relativePath = "Unknown";
		if(isLinked)
			parentFile.updateLinkedFile(this, relativePath);
	}
	
	public void setLocationRelative(String newName) {
		setFileLocation(new File(PA_ROOT_DIR + newName));
	}
	
	public boolean containsNonImageData() { //TODO temporary workaround for bigger problem
		return  numVBuffers!=0 || numIBuffers!=0 || numMaterials!=0 || numMeshes!=0 || numSkeletons!=0 || numModels!=0;
	}
	
	public String getFileName() {
		return fileName;
	}
	
	public String getRelativeFileName() {
		return relativePath;
	}
	
	public String getFilePath() {
		return filePath;
	}
	
	public File getFile() {
		return fileLocation;
	}
	
	public long getFileSize() {
		return fileSize;
	}
	
	public int getMajorVersion() {
		return majorVersion;
	}
	
	public int getMinorVersion() {
		return minorVersion;
	}
	
	public String getVersion() {
		return majorVersion+"."+minorVersion;
	}
	
	public int getNumStrings() {
		return numStrings;
	}
	
	public int getNumTextures() {
		return numTextures;
	}
	
	public boolean isPapaFile() {
		return isPapa;
	}
	
	public void generateLinkedTexture(PapaTexture t) {
		PapaFile p = generateLinkedFile(t.getName());
		t.attach(p);
		addTexture(new PapaTexture(t.getName(),this));
	}
	
	private PapaFile generateLinkedFile(String name) {
		PapaFile p = new PapaFile();
		p.setLocationRelative(name);
		linkedFiles.put(name, p);
		p.attach(this);
		return p;
	}

	void updateLinkedFile(PapaFile link, String newReference) {
		String originalName = getLinkName(link);
		linkedFiles.remove(originalName);
		addToLinkedFiles(newReference, link);
	}
	
	private void addToLinkedFiles(String name, PapaFile link) {
		//PapaFile p = linkedFiles.get(name);
		/*if(p!=null && !p.equals(link)) {
			throw new IllegalArgumentException("Every linked texture must point to a unique file");
		}*/
		linkedFiles.put(name, link);
	}

	private String getLinkName(PapaFile other) {
		if(other.getParent() != this)
			throw new IllegalArgumentException("PapaFile is not linked to this file");
		for(Entry<String,PapaFile> e : linkedFiles.entrySet())
			if(e.getValue() == other)
				return e.getKey();
		throw new IllegalStateException("A child linked file was not found in the parent PapaFile");
	}
	
	public void absorbLinkedTexture(PapaTexture t) {
		if(!t.isLinked())
			throw new IllegalArgumentException("Cannot absorb non linked texture");
		int index = textures.indexOf(t);
		if(index==-1)
			throw new IllegalArgumentException("Cannot absorb texture which is not owned by this PapaFile");
		PapaTexture link = textures.remove(index);
		PapaTexture target = link.getLinkedTexture();
		
		target.detach();
		link.detach();
		link.flush();
		target.attach(this);
		
		textures.add(target);
		recalculateFileSize();
	}
	
	public PapaTexture getTexture(int index) {
		return textures.get(index);
	}
	
	public PapaTexture getTexture(String name) {
		for(int i = 0; i< textures.size();i++)
			if(textures.get(i).getName().equals(name))
				return textures.get(i);
		throw new NoSuchElementException("Texture \""+name+"\" not found.");
	}
	
	public void addTexture(BufferedImage b, ImmutableTextureSettings t) throws IOException{
		textures.add(new PapaTexture(b, t, this));
		recalculateFileSize();
	}
	
	void addTexture(PapaTexture tex) {
		textures.add(tex);
		recalculateFileSize();
	}
	
	void removeTexture(PapaTexture tex) {
		PapaFile linkedFile = null;
		if(tex.isLinked() && tex.linkValid() && getReferencesToLinkedFile(tex.getLinkedTexture().getParent()) == 1) {
			linkedFile = tex.getLinkedTexture().getParent();
		}
		
		if(isLinked) {
			detach();
		}
		
		int index = textures.indexOf(tex);
		if(index==-1)
			throw new IllegalArgumentException("Cannot remove texture which does not belong to this papaFile");
		textures.remove(index);
		
		if(linkedFile!=null)
			linkedFile.detach();
		
		recalculateFileSize();
	}
	
	public boolean isLinkedFileReferenced(PapaFile other) {
		return getReferencesToLinkedFile(other)!=0;
	}
	
	private int getReferencesToLinkedFile(PapaFile other) {
		int count = 0;
		for(PapaTexture t : textures) {
			if(!t.isLinked())
				continue;
			if(t.getLinkedTexture().getParent()==other)
				count++;
		}
		return count;
	}

	public boolean containsLinkedFiles() {
		return linkedFiles.size()!=0;
	}
	
	public boolean containsLinkedFile(String linkName) {
		return linkedFiles.containsKey(linkName);
	}
	
	public PapaFile getLinkedFile(String linkName) {
		return linkedFiles.getOrDefault(linkName, DEFAULT);
	}
	
	public PapaFile[] getLinkedFiles() {
		return linkedFiles.values().toArray(new PapaFile[linkedFiles.size()]);
	}
	
	public boolean isLinkedFile() {
		return isLinked;
	}
	
	private void ensureLink() {
		if(!isLinked)
			throw new IllegalStateException("PapaFile is not linked!");
	}
	
	private void unlinkFile(PapaFile other, boolean removeDependencies) { // helper method for detach
		if(removeDependencies)
			removeDependencies(other);
		String key = getLinkName(other);
		linkedFiles.remove(key);
	}
	
	private void removeDependencies(PapaFile other) {
		boolean change = false;
		for(int i = 0; i<textures.size();i++) {
			PapaTexture t = textures.get(i);
			if(!t.isLinked())
				continue;
			if(t.getLinkedTexture().getParent() == other) {
				textures.remove(i--);
				change = true;
				t.flush();
			}
		}
		if(change)
			recalculateFileSize();
	}
	
	private void recalculateFileSize() {
		validateAll();
		fileSize = calcFileSize();
	}
	
	@Override
	public PapaFile getParent() {
		return parentFile;
	}
	
	public PapaFile getTopParentFile() { // this might be redundant.
		ensureLink();
		return getTopParentFileHelper();
	}
	
	private PapaFile getTopParentFileHelper() {
		if(!isLinked)
			return this;
		return parentFile.getTopParentFileHelper();
	}
	
	private void instantiate(String path, int flags) throws IOException {
		
		File f = new File(path);
		if(!f.exists())
			throw new IOException("File "+f.getPath()+" not found.");
		if(f.isDirectory())
			throw new IOException("File "+f.getPath()+" is a directory.");
		
		setFileLocation(f);
		
		try {
			in = new RandomAccessFile(f, "r"); // reads big endian
			
			fileSize = in.length();
			
			if(fileSize < 4)
				throw new IOException("File is empty"); // prevent null error
			readHeader(in);
			
			if( ! papaFileContains(flags))
				return;
			
			readStrings(in);
			readTextures(in);
			//readVBuffers(in);
			//readIBuffers(in);
			//readMaterials(in);
			//readMeshes(in);
			//readSkeletons(in);
			//readModels(in);
			//readAnimations(in);
		} catch (IOException e) {
			throw e;
		} finally {
			in.close();
		}
	}
	
	private boolean papaFileContains(int flags) {
		return true; //TODO
	}
	
	private void readHeader(RandomAccessFile in) throws IOException {
		signature = PapaFile.changeEndian(in.readInt());
		
		if(signature != (0x50<<24 | 0x61<<16 | 0x70<<8 | 0x61))
			throw new IOException("File signature does not match Papa specification.");
		
		minorVersion = 	PapaFile.changeEndian(in.readShort());
		majorVersion = 	PapaFile.changeEndian(in.readShort());
		
		numStrings = 	PapaFile.changeEndian(in.readShort());
		numTextures = 	PapaFile.changeEndian(in.readShort());
		numVBuffers = 	PapaFile.changeEndian(in.readShort());
		numIBuffers = 	PapaFile.changeEndian(in.readShort());
		
		numMaterials = 	PapaFile.changeEndian(in.readShort());
		numMeshes = 	PapaFile.changeEndian(in.readShort());
		numSkeletons = 	PapaFile.changeEndian(in.readShort());
		numModels = 	PapaFile.changeEndian(in.readShort());
		
		numAnimations = PapaFile.changeEndian(in.readShort());
		
		// padding 3
		in.readShort();
		in.readShort();
		in.readShort();
		
		offsetStringTable = 	PapaFile.changeEndian(in.readLong());
		offsetTextureTable = 	PapaFile.changeEndian(in.readLong());
		offsetVBufferTable = 	PapaFile.changeEndian(in.readLong());
		offsetIBufferTable = 	PapaFile.changeEndian(in.readLong());
		offsetMaterialTable = 	PapaFile.changeEndian(in.readLong());
		offsetMeshTable = 		PapaFile.changeEndian(in.readLong());
		offsetSkeletonTable = 	PapaFile.changeEndian(in.readLong());
		offsetModelTable = 		PapaFile.changeEndian(in.readLong());
		offsetAnimationTable = 	PapaFile.changeEndian(in.readLong());
	}
	
	private void readStrings(RandomAccessFile in) throws IOException {
		if (numStrings==-1)
			return;
		
		int[] length = 	new int[numStrings];
		int[] padding = new int[numStrings];
		long[] offset = new long[numStrings];
		
		in.seek(offsetStringTable);
		
		for(int i =0;i<numStrings;i++) {
			length[i] = 	changeEndian(in.readInt());
			padding[i] = 	changeEndian(in.readInt());
			offset[i] = 	changeEndian(in.readLong());
			
			if(padding[i] != 0)
				throw new IOException("Padding is not zero.");
		}
		
		for(int i=0;i<numStrings;i++) {
			in.seek(offset[i]);
			
			byte[] buf = new byte[length[i]];
			in.read(buf);
			
			String s = new String(buf);
			
			strings.add(new PapaString(s, padding[i],this));
		}
	}
	
	private void readTextures(RandomAccessFile in) throws IOException {
		if(numTextures == 0)
			return;
		in.seek(offsetTextureTable);
		
		short[] nameIndex = new short[numTextures];
		byte[] format = 	new byte[numTextures];
		byte mips[] = 		new byte[numTextures];
		boolean srgb[] = 	new boolean[numTextures];
		short[] width = 	new short[numTextures];
		short[] height = 	new short[numTextures];
		long[] size = 		new long[numTextures];
		long[] offset = 	new long[numTextures];
		
		for(int i =0;i<numTextures;i++) {
			nameIndex[i] = 	changeEndian(in.readShort());
			format[i] = 	in.readByte();
			byte input = 	in.readByte();
			mips[i] = 		(byte) (input&0b0111_1111);
			srgb[i] = 		(input & 0b1000_0000)==0b1000_0000;
			width[i] = 		changeEndian(in.readShort());
			height[i] = 	changeEndian(in.readShort());
			size[i] = 		changeEndian(in.readLong());
			offset[i] = 	changeEndian(in.readLong());
		}
		
		for(int i=0;i<numTextures;i++) {
			if(offset[i] >=0) {
				in.seek(offset[i]);
				byte[] buf = new byte[(int) size[i]];
				in.read(buf);
				
				textures.add(new PapaTexture(strings.get(nameIndex[i]).getValue(), format[i],
											mips[i], srgb[i], width[i], height[i], buf, this));
			} else { // file is linked
				if(PA_ROOT_DIR==null) 
					throw new IOException("Cannot load external images. Media directory not set.");
				String name = strings.get(nameIndex[i]).getValue();
				String fullPath = PapaFile.PA_ROOT_DIR + name;
				PapaFile p = openLinkedPapaFile(fullPath);
				if(p!=null)
					addToLinkedFiles(name, p);
				textures.add(new PapaTexture(name,this));
			}
			
		}
	}
	
	@Override
	public String toString() {
		return this.fileName;
	}
	
	private PapaFile openLinkedPapaFile(String fullPath) throws IOException{
		try {
			return PapaFile.readLinkedFile(fullPath,this);
		} catch (IOException e) {
			if(PapaFile.ERROR_IF_NOT_FOUND)
				throw new IOException("Failed to open linked file: "+e.getMessage());
			else {
				System.err.println("Failed to open linked file: "+e.getMessage());
				return null;
			}
		}
	}
	
	public byte[] getFileBytes() {
		if(fileBytes==null)
			throw new IllegalStateException("PapaFile is not built");
		return fileBytes;
	}
	
	public void build() {
		validateAll();
		byte[] bytes = new byte[calcFileSize()];
		ByteWriter fileBytes = new ByteWriter(bytes);
		fileBytes.seek(HEADER_SIZE);
		
		byte[] header = new byte[PapaFile.HEADER_SIZE];
		ByteWriter headerBuilder = new ByteWriter(header);
		buildHeader(headerBuilder);
		
		int offsetIndex = headerBuilder.index();
		for(int i =0;i<9;i++)
			headerBuilder.write((long)-1);
		headerBuilder.seek(offsetIndex + 8);
		
		// write all but strings
		for(int i=1;i<components.length;i++) {
			headerBuilder.write((long)fileBytes.index());
			buildComponent(components[i], fileBytes);
		}
		
		// write strings
		headerBuilder.seek(offsetIndex);
		headerBuilder.write((long)fileBytes.index());
		buildComponent(components[0], fileBytes);
		
		fileBytes.writeTo(header, 0);
		
		this.fileBytes = fileBytes.getData();
	}
	
	private int calcFileSize() {
		int totalSize = PapaFile.HEADER_SIZE;
		for(int i=0;i<components.length;i++)
			for(PapaComponent comp : components[i])
				totalSize+=comp.componentSize();
		return totalSize;
	}
	
	private void validateComponentCount() {
		this.numTextures = 	(short) textures.size();
		this.numStrings = 	(short) strings.size();
	}
	
	private void validateAll() {
		flushStringTable();
		for(int i=1;i<components.length;i++)
			validateComponent(components[i]);
		validateComponent(components[0]);
		validateComponentCount();
	}
	
	private void validateComponent(ArrayList<? extends PapaComponent> comp) {
		for(PapaComponent p : comp)
			p.validate();
	}
	
	private void buildHeader(ByteWriter b) {
		
		b.write("apaP");
		b.write((short)minorVersion);
		b.write((short)majorVersion);
		
		b.write((short)numStrings);
		b.write((short)numTextures);
		/*b.write((short)numVBuffers);
		b.write((short)numIBuffers);
		
		b.write((short)numMaterials);
		b.write((short)numMeshes);
		b.write((short)numSkeletons);
		b.write((short)numModels);
		
		b.write((short)numAnimations);*/
		b.write((short)0);
		b.write((short)0);
		
		b.write((short)0);
		b.write((short)0);
		b.write((short)0);
		b.write((short)0);
		
		b.write((short)0);
		b.write(0, 6);
	}
	
	private void buildComponent(ArrayList<? extends PapaComponent> comp, ByteWriter b) {
		int currentSize = 0;
		
		for(PapaComponent p : comp) {
			p.build();
			currentSize+=p.headerSize();
		}
		
		for(PapaComponent p : comp) {
			p.applyOffset(currentSize + b.index());
			currentSize+=ceilEight(p.bodySize());
		}
		
		for(PapaComponent p : comp)
			b.write(p.getHeaderBytes());
		for(PapaComponent p : comp) {
			b.write(p.getDataBytes());
			b.seek(ceilEight(b.index()));
		}
	}
	
	private void flushStringTable() {
		for(PapaString s : strings)
			s.flush();
		strings.clear();
	}
	
	public int getOrMakeString(String s) {
		for(int i = 0;i<strings.size();i++)
			if(strings.get(i).getValue().equals(s)) {
				return i;
			}
		
		strings.add(new PapaString(s,0,this));
		numStrings++;
		return strings.size() - 1;
	}
	
	@Override
	public void flush() {
		PapaFile[] entries = linkedFiles.values().toArray(new PapaFile[linkedFiles.size()]); // error if you directly access values() due to child files modifying the parent files
		for(PapaFile p : entries)
			p.flush();
		if(isLinked)
			detach();
		
		for(ArrayList<? extends PapaComponent> al : components)
			for(PapaComponent p : al)
				p.flush();
		strings = null;
		textures = null;
		fileBytes = null;
		fileName = null;
		linkedFiles = null;
		parentFile = null;
	}
	
	@Override
	protected void overwriteHelper(PapaComponent other) {// TODO
		
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj == null || !(obj.getClass().equals(PapaFile.class)))
			return false;
		if(obj==this)
			return true;
		
		PapaFile p = (PapaFile) obj;
		
		return 		(p.fileSize 		== 	fileSize)
				&&	(p.numAnimations 	== 	numAnimations)
				&&	(p.numIBuffers	 	== 	numIBuffers)
				&&	(p.numMaterials 	== 	numMaterials)
				&&	(p.numMeshes 		== 	numMeshes)
				&&	(p.numModels 		== 	numModels)
				&&	(p.numSkeletons 	== 	numSkeletons)
				&&	(p.numStrings	 	== 	numStrings)
				&&	(p.numTextures 		== 	numTextures)
				&&	(p.numVBuffers 		== 	numVBuffers)
				&&	(p.isLinked 		== 	isLinked)
				&&	(p.filePath.equals(		filePath))
				&&	(p.fileName.equals(		fileName))
				&&	(p.strings.equals(		strings))
				&&	(p.textures.equals(		textures))
				&&	(p.linkedFiles.equals(	linkedFiles));
		
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 17;
		result = prime * result + (int) (fileSize ^ fileSize>>>32);
		result = prime * result + (int) numAnimations;
		result = prime * result + (int) numIBuffers;
		result = prime * result + (int) numMaterials;
		result = prime * result + (int) numMeshes;
		result = prime * result + (int) numModels;
		result = prime * result + (int) numSkeletons;
		result = prime * result + (int) numStrings;
		result = prime * result + (int) numTextures;
		result = prime * result + (int) numVBuffers;
		result = prime * result + 		(isLinked ? 1 : 0);
		result = prime * result + 		filePath.hashCode();
		result = prime * result + 		fileName.hashCode();
		result = prime * result + 		strings.hashCode();
		result = prime * result + 		textures.hashCode();
		result = prime * result + 		linkedFiles.hashCode();
		return result;
	}
	//TODO: add multiple search paths
	/*
	public static String getSearchPaths()
	public static void addSearchPath(File f)
	 */
	
	public static File getPlanetaryAnnihilationDirectory() {
		return PA_ROOT_DIR;
	}
	
	public static void setPADirectory(File f) {
		PA_ROOT_DIR = f;
		if(f==null) {
			PA_ROOT_DIR_STRING = null;
			return;
		}
		PA_ROOT_DIR_STRING = f.getAbsolutePath().replace('\\', '/');
	}
	
	public static short changeEndian(short value)
	{
		int b1 = value 			& 0b1111_1111;
		int b2 = (value >> 8) 	& 0b1111_1111;
	  
		return (short) (b1 << 8 | b2);
	}
	
	  
	public static int changeEndian(int value)
	{
		int b1 = value 			& 0b1111_1111;
	  	int b2 = (value >>  8) 	& 0b1111_1111;
	  	int b3 = (value >> 16) 	& 0b1111_1111;
	  	int b4 = (value >> 24) 	& 0b1111_1111;
	
	  	return b1 << 24 | b2 << 16 | b3 << 8 | b4;
	}
	
	  
	public static long changeEndian(long value)
	{
		long b1 = value 		& 0b1111_1111;
		long b2 = (value >>  8) & 0b1111_1111;
	  	long b3 = (value >> 16) & 0b1111_1111;
	  	long b4 = (value >> 24) & 0b1111_1111;
	  	long b5 = (value >> 32) & 0b1111_1111;
	  	long b6 = (value >> 40) & 0b1111_1111;
	  	long b7 = (value >> 48) & 0b1111_1111;
	  	long b8 = (value >> 56) & 0b1111_1111;
	
	  	return b1 << 56 | b2 << 48 | b3 << 40 | b4 << 32 | b5 << 24 | b6 << 16 | b7 <<  8 | b8;
	}

	@Override
	protected void validate() {
		validateAll();
	}

	@Override
	protected int headerSize() {
		return PapaFile.HEADER_SIZE;
	}

	@Override
	protected int bodySize() {
		return (int) (fileSize - headerSize());
	}

	@Override
	protected void applyOffset(int offset) {}

	@Override
	public void detach() {
		ensureLink();
		parentFile.unlinkFile(this, false); // hard coded for now
		parentFile = null;
		isLinked = false;
	}

	@Override
	protected void setParent(PapaFile newParent) {
		if(isLinked)
			detach();
		isLinked = true;
		parentFile = newParent;
	}

	@Override
	public PapaFile duplicate() {
		return null;
	}

	public PapaFile getEmptyCopy() {
		PapaFile p = new PapaFile();
		p.setFileLocation(fileLocation);
		return p;
	}
}