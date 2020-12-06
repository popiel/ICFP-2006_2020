// ICFP-2006_2020.cpp : This file contains the 'main' function. Program execution begins and ends there.
//

#include <fcntl.h>
#include <io.h>
#include <stdio.h>
#include <stdlib.h>
#include <winsock.h>
#pragma comment(lib, "Ws2_32.lib")

typedef struct {
    unsigned int size;
    unsigned int when;
    unsigned int* data;
} tray;

typedef struct profile {
    unsigned int size;
    unsigned int count;
    struct profile* next;
    struct profile* times;
} profile;

struct profile* mem_profile = NULL;

void count_alloc(unsigned int size) {
    for (profile* p = mem_profile; p; p = p->next) {
        if (p->size == size) {
            p->count++;
            return;
        }
    }
    profile* p = (profile*)malloc(sizeof(profile));
    p->next = mem_profile;
    p->size = size;
    p->count = 1;
    p->times = NULL;
    mem_profile = p;
}

void count_free(unsigned int size, unsigned int ticks) {
    for (profile* p = mem_profile; p; p = p->next) {
        if (p->size == size) {
            for (profile* t = p->times; t; t = t->next) {
                if (t->size == ticks) {
                    t->count++;
                    return;
                }
            }
            profile* t = (profile*)malloc(sizeof(profile));
            t->next = p->times;
            t->size = ticks;
            t->count = 1;
            t->times = NULL;
            p->times = t;
            return;
        }
    }
}

void report_profile() {
    fprintf(stderr, "category,size,count,time\n");
    for (profile* p = mem_profile; p; p = p->next) {
        fprintf(stderr, "alloc,%d,%d\n", p->size, p->count);
        unsigned int eternal = p->count;
        for (profile* t = p->times; t; t = t->next) {
            fprintf(stderr, "free,%d,%d,%d\n", p->size, t->count, t->size);
            eternal -= t->count;
        }
        fprintf(stderr, "eternal,%d,%d\n", p->size, eternal);
    }
}

int main(int argc, char** argv)
{
    if (argc <= 1) {
        fprintf(stderr, "Usage: %s program.um\n", argv[0]);
        exit(1);
    }

    _setmode(_fileno(stdout), _O_BINARY);
    
    FILE* program;
    if (fopen_s(&program, argv[1], "rb") != S_OK) {
        fprintf(stderr, "Couldn't open file %s: ", argv[1]);
        perror("");
        exit(1);
    }
    fseek(program, 0, SEEK_END);
    size_t size = ftell(program);
    fseek(program, 0, SEEK_SET);
    unsigned int* a0 = (unsigned int*)malloc(size);
    size_t loaded = fread(a0, 1, size, program);
    if (loaded != size) {
        fprintf(stderr, "Could only load %d of %d bytes of program %s\n", loaded, size, argv[1]);
        exit(1);
    }
    fclose(program);
    for (unsigned int j = 0; j < size / 4; j++) {
        a0[j] = ntohl(a0[j]);
    }

    unsigned int registers[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };
    unsigned int finger = 0;
    unsigned int num_arrays = 32;
    tray* arrays = (tray*)calloc(num_arrays, sizeof(tray));
    arrays[0].data = a0;
    arrays[0].size = size / 4;
    for (unsigned int j = 1; j < num_arrays; j++) arrays[j].size = (j + 1) % num_arrays;
    unsigned int next_free = 1;

    for (unsigned int tick = 0;; tick++)  {
        /*
        if (finger >= arrays[0].size) {
            fprintf(stderr, "Out of bounds finger %d (size is %d)\n", finger, arrays[0].size);
            exit(1);
        }
        */
        unsigned int op = arrays[0].data[finger];
        finger++;
#define A (registers[(op >> 6) & 7])
#define B (registers[(op >> 3) & 7])
#define C (registers[(op >> 0) & 7])
#define D (registers[(op >> 25) & 7])
#define V (op & 0x01ffffff)
        switch ((op >> 28) & 15) {
        case 0: if (C) A = B;          break;
        case 1:
            /*
            if (C >= arrays[B].size) {
                fprintf(stderr, "Out of bounds read %d:%d (size is %d)\n", B, C, arrays[B].size);
                exit(1);
            } else
            */
                A = arrays[B].data[C];
            break;
        case 2:
            /*
            if (B >= arrays[A].size) {
                fprintf(stderr, "Out of bounds write %d:%d (size is %d)\n", A, B, arrays[A].size);
                exit(1);
            }
            else
            */
                arrays[A].data[B] = C;
            break;
        case 3: A = B + C;             break;
        case 4: A = B * C;             break;
        case 5: A = B / C;             break;
        case 6: A = ~(B & C);          break;
        case 7: fprintf(stderr, "clean exit\n"); report_profile();  exit(0); break;
        case 8:
            if (!next_free) {
                next_free = num_arrays;
                num_arrays *= 2;
                // fprintf(stderr, "Growing array list from %d to %d entries\n", next_free, num_arrays);
                arrays = (tray*)realloc(arrays, num_arrays * sizeof(tray));
                for (unsigned int j = next_free; j < num_arrays; j++) {
                    arrays[j].size = (j + 1) % num_arrays;
                }
            }
            {
                unsigned int loc = next_free;
                next_free = arrays[next_free].size;
                // fprintf(stderr, "allocating size %d at %d\n", C, loc);
                count_alloc(C);
                arrays[loc].when = tick;
                arrays[loc].size = C;
                arrays[loc].data = (unsigned int*)calloc(arrays[loc].size, sizeof(unsigned int));
                memset(arrays[loc].data, 0, C * sizeof(unsigned int));
                B = loc;
            }
            break;
        case 9:
            // fprintf(stderr, "freeing size %d at %d\n", arrays[C].size, C);
            count_free(arrays[C].size, tick - arrays[C].when);
            free(arrays[C].data);
            arrays[C].size = next_free;
            next_free = C;
            break;
        case 10: putchar(C & 255); fflush(stdout); break;
        case 11:
            C = getchar();
            if (C == EOF) {
                fprintf(stderr, "EOF exit\n");
                report_profile();
                exit(0);
            }
            break;
        case 12:
            if (B) {
                free(arrays[0].data);
                arrays[0].data = (unsigned int*)calloc(arrays[B].size, sizeof(unsigned int));
                memcpy(arrays[0].data, arrays[B].data, arrays[B].size * sizeof(unsigned int));
                arrays[0].size = arrays[B].size;
            }
            // fprintf(stderr, "jump to %d:%d\n", B, C);
            finger = C;
            break;
        case 13:
            D = V;
            break;
        default:
            fprintf(stderr, "illegal operator at %d: %08x\n", finger - 1, op);
        }
    }
}
