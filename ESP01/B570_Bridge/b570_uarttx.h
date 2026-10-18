// b570_uarttx.h - 不阻塞的 UART 傳送佇列（ESP → STM32）
// 能直接放進硬體 TX FIFO 就直接寫（平常一定是這條路：上行負載只佔 230400 bps 的 ~3%），
// FIFO 滿時其餘排入環形佇列，每圈 pump() 補寫；整幀進或整幀丟，絕不忙等。
// 純 C++（無 Arduino 相依）：Port 需提供 size_t room()（不會阻塞的可寫 bytes）與 write(const uint8_t*, size_t)。
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace b570 {

template <size_t N>
class TxRing {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "TxRing size must be a power of two");

public:
    uint32_t drops = 0;   // 佇列滿而丟棄的幀（不應發生）

    size_t used() const { return (head_ - tail_) & (N - 1); }
    size_t capacity() const { return N - 1; }

    // 把佇列中的資料盡量寫進 FIFO
    template <class Port>
    void pump(Port& port) {
        while (used()) {
            const size_t room = port.room();
            if (!room) return;
            const size_t contig = (head_ > tail_) ? head_ - tail_ : N - tail_;
            const size_t k = room < contig ? room : contig;
            port.write(buf_ + tail_, k);
            tail_ = (tail_ + k) & (N - 1);
        }
    }

    // 送出一整幀：先補寫舊資料（保持順序），佇列空時直接寫 FIFO，剩下的排隊
    template <class Port>
    bool send(Port& port, const uint8_t* d, size_t n) {
        pump(port);
        size_t direct = 0;
        if (used() == 0) {
            direct = port.room();
            if (direct > n) direct = n;
        }
        if (n - direct > capacity() - used()) {
            ++drops;
            return false;
        }
        if (direct) port.write(d, direct);
        for (size_t i = direct; i < n; ++i) {
            buf_[head_] = d[i];
            head_ = (head_ + 1) & (N - 1);
        }
        return true;
    }

private:
    uint8_t buf_[N];
    size_t head_ = 0, tail_ = 0;
};

}  // namespace b570
