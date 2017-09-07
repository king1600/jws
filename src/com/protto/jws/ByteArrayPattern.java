package com.protto.jws;

public class ByteArrayPattern {

  private int i, j;
  private int[] table;
  private byte[] pattern;
  
  public ByteArrayPattern(final byte[] pattern) {
    this.pattern = pattern;
    table = new int[pattern.length < 2 ? 2 : pattern.length];
    
    i = 2;
    j = 0;
    table[0] = -1;
    table[1] =  0;
    
    while (i < pattern.length) {
      if (pattern[i - 1] == pattern[j])
        table[i++] = ++j;
      else if (j > 0)
        j = table[j];
      else
        table[i++] = 0;
    }
  }
  
  public int size() {
    return pattern.length;
  }
  
  public int find(final byte[] search, final int start, final int size) {
    if (pattern.length == 0)
      return 0;
    if (size == 0)
      return -1;
    
    j = 0;
    i = 0;
    
    while (j + i + start < size) {
      if (pattern[i] == search[j + i + start]) {
        if (i == pattern.length - 1)
          return j;
        i++;
      } else {
        j = (j + i - table[i]);
        i = table[i] > -1 ? table[i] : 0;
      }
    }
    
    return -1;
  }
}
