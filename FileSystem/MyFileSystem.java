package fileSystem;
import java.io.*;

/**
 * Unit tests are in {@see TestMyFileSystem}. See MyFileSystem.java.
 */
public class MyFileSystem implements FileSystem {
    public Disk       disk       = new Disk();
    public FileTable  fileTable  = new FileTable();
    public SuperBlock superBlock = new SuperBlock();
    public FreeMap    freeMap;

    /**
     * Reading from or writing to a file.
     */
    private enum MODE { w, r };

    /**
     * Construct a new FileSystem. You are responsible for calling
     * formatDisk on the new FileSystem if necessary.
     */
    public MyFileSystem() throws IOException {
        disk.read(0, superBlock);
        initFreeMap();
    }
    
    public int formatDisk(int size, int isize) throws IOException {
        // The total size of the file system cannot be larger than the
        // maximum size of the disk.
        //
        if(size > Disk.NUM_BLOCKS) {
            System.err.println("Size exceeds disk size of " + Disk.NUM_BLOCKS);
            return -1;
        }
        
        // Calculate the number of blocks needed for the freemap (may
        // be 0 if the entire free map fits within the superblock.
        //
        int extra = (size - isize - 1) - superBlock.freeMap.length * 8;
        int msize = (int)Math.max(0, Math.ceil(extra / 8.0 / Disk.BLOCK_SIZE));
        
        // We require that the size of the metadata not exceed the
        // size of the file system.
        //
        if(size - msize - isize - 1 < 0) {
            System.err.println("Metadata will not fit in file system");
            return -1;
        }

        // Initialize and write the superblock.
        superBlock.size  = size;
        superBlock.isize = isize;
        superBlock.msize = msize;
        disk.write(0, superBlock);

        // Write empty FreeMapBlocks (if needed) and InodeBlocks, with
        // FreeMapBlocks immediately following the SuperBlock and
        // InodeBlocks immediately following the FreeMapBlocks.
        //
        if(superBlock.mblock0() > 0)
            for(int i = superBlock.mblock0(); i < superBlock.iblock0(); ++i)
                disk.write(i, new FreeMapBlock());
        for(int i = superBlock.iblock0(); i < superBlock.dblock0(); ++i)
            disk.write(i, new InodeBlock());

        // Set up the free map again (because we changed file system
        // metadata since the constructor was called).
        //
        initFreeMap();

        return 0;
    }
    
    public int shutdown() throws IOException {
        // Save any free map blocks that haven't been written
        freeMap.save();

        // Close any open files
        for(int fd = 0; fd < FileTable.MAX_FILES; ++fd)
            if(fileTable.isValid(fd))
                close(fd);

        // Stop the disk and end
        disk.stop(false);
        return 0;
    }
    
    public int create() throws IOException {
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Try to find an inode for the new file.
        //
        InodeBlock block = new InodeBlock();
        int inumber = 1; // inumbers start at 1, not 0
        for(int n = superBlock.iblock0(); n < superBlock.dblock0(); ++n) {
            disk.read(n, block);
            for(int o = 0; o < InodeBlock.COUNT; ++o, ++inumber) {
                if(block.inodes[o].flags == 0) {
                    block.inodes[o].allocate();
                    fileTable.add(block.inodes[o], inumber, fd);
                    disk.write(n, block);
                    return fd;
                }
            }
        }

        // Could not find a free inode, so release our file
        // descriptor, print an error message, and finish.
        //
        fileTable.free(fd);
        System.err.println("Out of files");
        return -1;
    }
    
    public int open(int inumber) throws IOException {
        if(! inumberIsValid(inumber))
            return -1;
        
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Get the requested inode from disk.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // If the inode is allocated, associate the inode with the
        // file descriptor, and then return the fd. Otherwise, there
        // was an error so we should release the file descriptor and
        // return -1.
        //
        if(inode.flags != 0) {
            fileTable.add(inode, inumber, fd);
            return fd;
        }
        fileTable.free(fd);
        System.err.println("File " + inumber + " does not exist");
        return -1;
    }
    
    public int inumber(int fd) throws IOException {
        return fileTable.getInumber(fd);
    }
    
    public int read(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0, limit = getReadLimit(fd, buffer.length);
        for(off = 0; off < limit; off += len) {
            block = getDirectBlock(fd, MODE.r);
            len = block.copyTo(buffer, off); // may copy some garbage in
            seek(fd, len, Whence.SEEK_CUR);
        }
        return limit;
    }
    
    public int write(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0;
        for(off = 0; off < buffer.length; off += len) {
            if((block = getDirectBlock(fd, MODE.w)) == null) {
                System.err.println("File system is full");
                return -1;
            }
            len = block.copyFrom(buffer, off);
            seek(fd, len, Whence.SEEK_CUR);
            updateFileSize(fd);
            block.save();
        }
        return buffer.length;
    }
    
    public int seek(int fd, int offset, Whence whence) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;
        
        Inode inode = fileTable.getInode(fd);
        int ptr;
        
        switch(whence) {
            case SEEK_SET:
                ptr = offset;
                break;
            case SEEK_END:
                ptr = offset + inode.size;
                break;
            case SEEK_CUR:
                ptr = offset + fileTable.getSeekPointer(fd);
                break;
            default:
                return -1;
        }
        if(ptr < 0) {
            System.err.println("Cannot seek to offset < 0");
            return -1;
        }
        fileTable.setSeekPointer(fd, ptr);
        return ptr;
    }
    
    public int close(int fd) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        // Read the InodeBlock in, modify it, and write it back out.
        //
        InodeBlock inodeBlock = new InodeBlock();
        int inumber = fileTable.getInumber(fd);
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        inodeBlock.inodes[inumberToOffset(inumber)] = fileTable.getInode(fd);
        disk.write(inumberToBlockNum(inumber), inodeBlock);

        // Free the file descriptor and return successfully.
        //
        fileTable.free(fd);
        return 0;
    }
    
    public int delete(int inumber) throws IOException {
        // Disallow deleting of open files.
        //
        int fd;
        if((fd = fileTable.getFdFromInumber(inumber)) != -1) {
            System.err.println("Cannot delete open file (fd = " + fd + ")");
            return -1;
        }

        // Get inode for this file.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // Free all direct blocks in the free map. No need to clear
        // the inode pointers, they are cleared when allocating a new
        // file.
        //
        for(int i = 0; i < inode.ptr.length; ++i)
            if(inode.ptr[i] != 0)
                freeMap.clear(inode.ptr[i]);
        freeMap.save();

        // Mark the inode as free and write it to disk.
        //
        inode.flags = 0;
        disk.write(inumberToBlockNum(inumber), inodeBlock);
        return 0;
    }

    /**
     * Initialize the freeMap instance. Should be called at the end of
     * the constructor and from formatDisk.
     */
    private void initFreeMap() {
        freeMap = new FreeMap(disk, superBlock);
    }

    /**
     * Ensure that the fd is within the valid range and refers to an
     * open file. Prints an error message if it is invalid.
     *
     * @return boolean true if fd is valid, false otherwise
     */
    private boolean fileDescriptorIsValid(int fd) {
        if(( fd < 0                         ||
             fd >= FileTable.MAX_FILES      ||
             fileTable.getInode(fd) == null )) {
            System.err.println("File descriptor " + fd + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Ensure that the inumber is within the valid range. Prints an
     * error message if it is invalid.
     *
     * @return boolean true if inumber is valid, false otherwise
     */
    private boolean inumberIsValid(int inumber) {
        if(inumber <= 0 || inumber >= superBlock.isize * InodeBlock.COUNT) {
            System.err.println("inumber " + inumber + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Get a DirectBlock object representing the direct block given
     * the current seek position in the open file identified by fd. A
     * DirectBlock references the direct block and offset within that
     * block containing the current seek position.
     *
     * If the current seek position is within a hole or beyond the end
     * of a file, then if create is true then a block will be
     * allocated to fill the hole. If there is no more free space in
     * the file system, null will be returned. If the seek position is
     * in a hole and create is false, then a block containing zeroes
     * will be returned.
     *
     * @param   fd          valid file descriptor of an open file
     * @param   mode        MODE.w if holes should be filled, MODE.r
     *                      otherwise (holes will be read as blocks
     *                      of all zeros)
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock getDirectBlock(int fd, MODE mode) {
    	//MODE.r for read; MODE.w for write.
    	//fd is valid file descriptor of an open file.
    	//return a DirectBlock object representing the direct block given the current seek position in the open file identified by fd.
    	Inode inode   = fileTable.getInode(fd);
        //points somewhere inside the open file.
        int seekPtr   = fileTable.getSeekPointer(fd);
        //logical block number referring to a data block.
        int blockNum  = seekPtr/Disk.BLOCK_SIZE;
        //offset within that block that the seek pointer is at.
        int blockOff  = seekPtr%Disk.BLOCK_SIZE;

        
        if(blockNum > 9) {
            //the following will list all the conditions and we will deal with them.
        	
        	//clarify the number for single indirection.
        	int numOfS;
        	//clarify the number for double indirection.
        	int numOfD;
        	//clarify the number for triple indirection.
        	int numOfT;
        	//calculate the number for single indirection.
        	numOfS = IndirectBlock.COUNT;
        	//calculate the number for double indirection.
        	numOfD = IndirectBlock.COUNT * IndirectBlock.COUNT;
        	//calculate the number for triple indirection.
        	numOfT = IndirectBlock.COUNT * IndirectBlock.COUNT * IndirectBlock.COUNT;
        	//clarify three new object of IndirectBlock type.
        	IndirectBlock firstIB=new IndirectBlock();
        	//clarify three new object of IndirectBlock type.
        	IndirectBlock secondIB=new IndirectBlock();
        	//clarify three new object of IndirectBlock type.
        	IndirectBlock thirdIB=new IndirectBlock();
        	
        	//start with the situation: over the limit of the number.
        	if(blockNum-numOfS-numOfD-numOfT>9){
        		//print out the error message for reminder.
        		System.err.println("Large files unsupported");
                System.exit(1);
        	}
        	
        	//the first situation: triple indirection.
        	//we check if it belongs to this condition using math to calculate.
        	else if(blockNum-numOfS-numOfD>9){
        		int num1,num2,num3,num4;
        		//num1 is the number we separate after minus single number, double number and 10.
        		num1=blockNum-10-numOfD-numOfS;
        		//num2 is the number we use num1 to define, used to be the index of the pointer to point to second indirect block.
        		num2=num1/numOfD;
        		//also, similarly, num3 is the number we use num2 to define, used to be the index of the pointer to point to the first indirect block.
        		num3=(num1-num2*numOfD)/numOfS;
        		//num4 is the number we use for offset.
        		num4=(num1-num2*numOfD)%numOfS;
        		
        		//we check if there is a hole in the file by looking at if ptr[12] equals to 0.
        		if(inode.ptr[12]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w.
        				if ((inode.ptr[12]=freeMap.find())==0)
        					//return null if freeMap.find() works.
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(inode.ptr[12], thirdIB);}				
        			}  				
        		}
        		else{
        		//use disk.read with the third IndirectBlock to be pointed to.
        		disk.read(inode.ptr[12], thirdIB);}
        		
        		//check if the third IndirectBlock's pointer equals to 0.
        		if(thirdIB.ptr[num2]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w.
        				if ((thirdIB.ptr[num2]=freeMap.find())==0)
        					//We return null if freeMap.find() works.
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(thirdIB.ptr[num2], secondIB);}
        			} 				
        		}
        		else{	
        		//use disk.read with the second IndirectBlock to be pointed to by the third one.
        		disk.read(thirdIB.ptr[num2], secondIB);}
        		
        		//if the second IndirectBlock's pointer ptr[num3] equals to 0.
        		if(secondIB.ptr[num3]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w.      				
        				if ((secondIB.ptr[num3]=freeMap.find())==0)
        					//We return null if freeMap.find() works.
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(secondIB.ptr[num3], firstIB);}
        			}   				
        		}
        		else{
        		//use disk.read with the first IndirectBlock to be pointed to by the second one.
        		disk.read(secondIB.ptr[num3], firstIB);}
        		
        		//create a new boolean variable.
                boolean flag=firstIB.ptr[num4]==0;          
        		if (flag){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode == MODE.r)
        				return DirectBlock.hole;
        			else{
        				//if the MODE is MODE.w.
        				//check if the first IndirectBlock's pointer can be found in freeMap.
        				if ((firstIB.ptr[num4]=freeMap.find())==0)
        					{return null;}
        				//because we have allocate a new free block, we need to rewrite the content to it.
        				else{
        					//create a new DirectBlock
        					DirectBlock newOne;
        					newOne=new DirectBlock(disk, firstIB.ptr[num4], blockOff, flag);
        					//disk.write the first indirect block.
        					disk.write(secondIB.ptr[num3], firstIB);
        					//disk.write the second indirect block.
        					disk.write(thirdIB.ptr[num2], secondIB);
        					//disk.write the third indirect block.
        					disk.write(inode.ptr[12], thirdIB);
        					//return this direct block.
        					return newOne;
        				}
        			}        				
        		}
        		//return the content.
        		return new DirectBlock(disk, firstIB.ptr[num4], blockOff, flag);
        	}
        	
        	//the second situation: double indirection.
        	//we check if it belongs to this condition using math to calculate.
        	else if(blockNum-numOfS>9){
        		int num1,num2,num3;
        		//num1 is the number we separate after minus single number and 10.
        		num1=blockNum-10-numOfS;
        		//num2 is the number we use num1 to define, used to be the index of the pointer to point to first indirect block.
        		num2=num1/numOfS;
        		//num3 is the number we use for offset.
        		num3=num1%numOfS;
        		
        		//we check if there is a hole in the file by looking at if ptr[11] equals to 0.
        		if(inode.ptr[11]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w. 
        				if ((inode.ptr[11]=freeMap.find())==0)
        					//We return null if freeMap.find() works.
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(inode.ptr[11], secondIB);}
        			}		
        		}
        		else{
        		//use disk.read with the pointer points to the second IndirectBlock.
        		disk.read(inode.ptr[11], secondIB);}
        		
        		//if the second IndirectBlock's pointer ptr[num2] equals to 0.        		
        		if(secondIB.ptr[num2]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w. 
        				if ((secondIB.ptr[num2]=freeMap.find())==0)
        					//We return null if freeMap.find() works.
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(secondIB.ptr[num2], firstIB);}
        			}	
        		}
        		else{
        		//use disk.read with the pointer points to the first IndirectBlock.
        		disk.read(secondIB.ptr[num2], firstIB);}
        		
        		//create a new boolean variable.
                boolean flag=firstIB.ptr[num3]==0;               
        		if (flag){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w.
        				//check if the first IndirectBlock's pointer can be found in freeMap.
        				if ((firstIB.ptr[num3]=freeMap.find())==0)
        					//We return null if freeMap.find() works.
        					{return null;}
        				//because we have allocate a new free block, we need to rewrite the content to it.
        				else{
        					//create a new DirectBlock.
        					DirectBlock newOne;
        					newOne=new DirectBlock(disk, firstIB.ptr[num3], blockOff, flag);
        					//disk.write the first indirect block.
        					disk.write(secondIB.ptr[num2], firstIB);
        					//disk.write the second indirect block.
        					disk.write(inode.ptr[11], secondIB);
        					//return this direct block.
        					return newOne;
        				}
        			}       				
        		}
        		return new DirectBlock(disk, firstIB.ptr[num3], blockOff, flag);       		
        	}
        	
        	//the final situation: single indirection.
        	//we check if it belongs to this condition using math to calculate.
        	else{
        		//num1 is the number we separate after minus 10.
        		//we use i to be the index of the pointer to point to the the first indirect block.
        		int num1=blockNum-10;
        		//if the first IndirectBlock's pointer ptr[i] equals to 0.
        		if(inode.ptr[10]==0){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				{return DirectBlock.hole;}
        			else{
        				//if the MODE is MODE.w.
        				if ((inode.ptr[10]=freeMap.find())==0)
        					//We return null if freeMap.find() works
        					{return null;}
        				else
        					//otherwise, use disk.write to write.
        					{disk.write(inode.ptr[10], firstIB);}
        			}		
        		}
        		else{
        		//use disk.read with the pointer points to the first IndirectBlock.
        		disk.read(inode.ptr[10], firstIB);}
        		
        		//create a new boolean variable.
        		boolean flag=firstIB.ptr[num1]==0;        		
        		if (flag){
        			//if the MODE is MODE.r, then return the hole.
        			if (mode==MODE.r)
        				return DirectBlock.hole;
        			else{
        				//if the MODE is MODE.w.
        				//check if the first IndirectBlock's pointer can be found in freeMap.
        				if ((firstIB.ptr[num1]=freeMap.find())==0)
        					//We return null if freeMap.find() works
        					{return null;}
        				else{
            				//because we have allocate a new free block, we need to rewrite the content to it.
        					DirectBlock newOne;
        					newOne=new DirectBlock(disk, firstIB.ptr[num1], blockOff, flag);
        					//disk.write the first indirect block.
        					disk.write(inode.ptr[10], firstIB);
        					//return this direct block.
        					return newOne;
        				}
        			}	
        		}
        		return new DirectBlock(disk, firstIB.ptr[num1], blockOff, flag);	
        	}	
        }
            
        boolean fresh = inode.ptr[blockNum] == 0;
		// The blockNum is a logical block number referring to a
		// pointer in the inode.
		if (fresh){
			if (mode == MODE.r)
				return DirectBlock.hole;
			else if ((inode.ptr[blockNum] = freeMap.find()) == 0)
				return null;
		}
		return new DirectBlock(disk, inode.ptr[blockNum], blockOff, fresh);        
    }

    /**
     * Convert an inumber to the number of the InodeBlock that
     * contains it.
     *
     * @param inumber inumber of inode to locate
     * @return int block number of InodeBlock
     */
    private int inumberToBlockNum(int inumber) {
        return superBlock.iblock0() + (inumber - 1) / InodeBlock.COUNT;
    }

    /**
     * Convert an inumber to its offset within its InodeBlock.
     *
     * @param inumber inumber of the inode to locate
     * @return int offset of inode within its InodeBlock
     */
    private int inumberToOffset(int inumber) {
        return (inumber - 1) % InodeBlock.COUNT;
    }

    /**
     * Update the size of a file if needed so that it is always at
     * least as large as the current seek pointer.
     */
    private void updateFileSize(int fd) {
        int currentSize = fileTable.getInode(fd).size;
        int seekPointer = fileTable.getSeekPointer(fd);
        if(seekPointer > currentSize)
            fileTable.setFileSize(fd, seekPointer);
    }

    /**
     * Get the maximum number of bytes that can be read from open file
     * fd into a buffer of length len. If the seek pointer is beyond
     * the end of the file, always returns 0 (since nothing can be
     * read beyond the end of a file).
     */
    private int getReadLimit(int fd, int len) {
        int rest = fileTable.getInode(fd).size - fileTable.getSeekPointer(fd);
        return Math.max(0, Math.min(len, rest));
    }
}
