# Notes

- https://netbeez.net/blog/how-to-use-the-linux-traffic-control/

<pre>
tc qdisc add dev eth0 root netem delay 5000ms
tc qdisc del root dev eth0
tc qdisc show  dev eth0
</pre>