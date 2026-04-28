def x25crc(buf):
    crc = 0xffff
    for b in buf:
        tmp = b ^ (crc & 0xff)
        tmp = (tmp ^ (tmp << 4)) & 0xFF
        crc = (crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)
    return crc & 0xFFFF

def wrong_kotlin_crc(buf):
    crc = 0xffff
    for b in buf:
        tmp = b ^ (crc & 0xff)
        tmp = tmp ^ (tmp << 4) # MISSING & 0xFF
        crc = (crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)
    return crc & 0xFFFF

buf = [9, 0, 1, 1, 0, 0, 0, 0, 0, 2, 3, 81, 4, 3, 50]
print("Correct:", hex(x25crc(buf)))
print("Kotlin:", hex(wrong_kotlin_crc(buf)))
