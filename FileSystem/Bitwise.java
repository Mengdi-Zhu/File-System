package fileSystem;

/**
 * Manage bitwise operations in a byte or array of bytes.
 *
 * Unit tests are in {@see TestBitwise}. See TestBitwise.java.
 */
public class Bitwise {
	
    private static final int bitmasks[] = {1, 2, 4, 8, 16, 32, 64, 128};

    /**
     * Check to see if bit i is set in byte. Returns true if it is
     * set, false otherwise.
     */
    public static boolean isset(int i, byte b) {
    	/**
    	 * do "and" operation for every bit,if the result is not zero,the bit in i is set in bit
    	 * for example:
    	 * i=3=00001000
    	 * b=12=00001100
    	 * i&b=00001000!=0 so the bit in 3 is set in 12 	
    	 */
    	return (bitmasks[i]&b)!=0;
    }

    /**
     * Check to see if bit i is set in array of bytes. Returns true if
     * it is set, false otherwise.
     */
    public static boolean isset(int i, byte bytes[]) {
    	int size = bytes.length-1;
    	/**
    	 * size is the max index of bytes
    	 * the point is how to find the right position in the byte array for i
    	 * for example i = 8,the bytes is{10001000,00000001}
    	 * 8 is out of the range of 00000001,so we must do the job with 10001000,
    	 * 8-8=0,so we can decide the bit on 0 is set or no for 10001000
    	 */   	
    	while(i>=8){
    		i=i-8;
    		size--;
    	}
    	return isset(i,bytes[size]);
    }

    /**
     * Set bit i in byte and return the new byte.
     */
    public static byte set(int i, byte b) {
    	//This is similar to isset(int i,byte b)
    	return (byte)(bitmasks[i]|b);
    }

    /**
     * Set bit i in array of bytes.
     */
    public static void set(int i, byte bytes[]) {
    	int size = bytes.length-1;
    	//This is similar to isset(int i,byte[] bytes)
    	while(i>=8){
    		i=i-8;
    		//from the rightmost bit to leftmost bit.
    		size--;
    	}
    	bytes[size]=set(i,bytes[size]);
    }

    /**
     * Clear bit i in byte and return the new byte.
     */
    public static byte clear(int i, byte b) {
    	if(isset(i, b))
    		//clear bit i in byte if check it is set.
        	{return  (byte)(bitmasks[i]^b);}
        else
        	//otherwise
        	{return b;}
    }

    /**
     * Clear bit i in array of bytes and return true if the bit was 1
     * before clearing, false otherwise.
     */
    public static boolean clear(int i, byte bytes[]) {
    	//create a boolean variable.
    	boolean result = isset(i, bytes);
    	int size = bytes.length-1;
    	//similar to above.
    	while(i>=8){
    		i=i-8;
    		size--;
    	}
    	if(result)	
    	bytes[size]=clear(i,bytes[size]);
    	return result;
    }

    /**
     * Clear every bit in array of bytes.
     *
     * There is no clearAll for a single byte, you can just get a new
     * byte for that.
     */
    public static void clearAll(byte bytes[]) {
        for(int i = 0; i < bytes.length; ++i) {
            bytes[i] = 0;
        }
    }

    /**
     * Convert byte to a string of bits. Each bit is represented as
     * "0" if it is clear, "1" if it is set.
     */
    public static String toString(byte b) {
    	  String s = "";
    	  int j=7;
    	
    	  while(j>=0)
    	  {   		
    		  s+=isset(j,b)?1:0;
    		  j--;
    	  }
    	  return s;
    }

    /**
     * Convert array of bytes to string of bits (each byte converted
     * to a string by calling {@link #byteToString(byte b)}, every
     * byte separated by sep, every "every" bytes separated by lsep.
     */
    public static String toString(byte bytes[], String sep,
                                  String lsep, int every) {
        String s = "";
        for(int i = bytes.length * 8 - 1; i >= 0; --i) {
        	s += isset(i, bytes) ? "1" : "0";
        	if(i > 0)
                if(every > 0 && i % (8 * every) == 0)
                    s += lsep;
                else if(i % 8 == 0)
                    s += sep;
        }
        return s;
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by sep. See {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[], String sep) {
        return toString(bytes, sep, null, 0);
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by a comma, and every 8 bytes separated by a newline. See
     * {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[]) {
        return toString(bytes, ",", "\n", 8);
    }
}