package edu.mit.haoqili.camera_cloud;
import java.io.Serializable;

/** Identifies a region. Really just a pair of long, long */
public final class RegionKey implements Serializable {
	private static final long serialVersionUID = 1L;

	public long x;
	public long y;

	public RegionKey() {
		this.x = 0;
		this.y = 0;
	}
	
	public RegionKey(long x, long y) {
		this.x = x;
		this.y = y;
	}

	public RegionKey(RegionKey orig) {
		this.x = orig.x;
		this.y = orig.y;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		RegionKey other = (RegionKey) obj;
		if (x != other.x)
			return false;
		if (y != other.y)
			return false;
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (x ^ (x >>> 32));
		result = prime * result + (int) (y ^ (y >>> 32));
		return result;
	}
	
	@Override
	public String toString() {
		return String.format("(%d,%d)", x, y);
	}
}
