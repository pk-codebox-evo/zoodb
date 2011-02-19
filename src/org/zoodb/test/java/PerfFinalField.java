package org.zoodb.test.java;

import org.junit.Test;

public class PerfFinalField {

	private long timer;
	
	private static class TestMe {
		private final int x;
		private int y;
		TestMe(int x) {
			this.x = x;
		}
		void setY(int y) {
			this.y = y;
		}
		int getX() {
			return x;
		}
		int getY() {
			return y;
		}
	}
	
	private static final int N = 100000;

	private static class TestThread extends Thread {
		
		private TestMe[] a;
		
		@Override
		public void run() {
			for (int i = 0; i < N; i++) {
				a[i] = new TestMe(1);
				a[i].setY(1);
			}
		}
	}
	
	
	@Test
	public void test() {
		TestMe[] a = new TestMe[N];
//		for (int i = 0; i < N; i++) {
//			a[i] = new TestMe(i);
//			a[i].setY(i);
//		}
		TestThread tt = new TestThread();
		tt.a = a;
		tt.start();
		try {
			tt.join();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		
		long n = 0;
		for (int i = 0; i < 100; i++) {
			for (int j = 0; j < N; j++) {
				n += a[j].x;
			}
			for (int j = 0; j < N; j++) {
				n += a[j].y;
			}
		}

		start();
		for (int i = 0; i < 100; i++) {
			for (int j = 0; j < N; j++) {
				n += a[j].x;
			}
		}
		stop("final X");
		
		start();
		for (int i = 0; i < 100; i++) {
			for (int j = 0; j < N; j++) {
				n += a[j].y;
			}
		}
		stop("y");
		
		System.out.println(n);
	}
	
	
	private void start() {
		timer = System.currentTimeMillis();
	}
	
	private void stop(String str) {
		long t = System.currentTimeMillis() - timer;
		System.out.println(str + ": " + t);
	}
}